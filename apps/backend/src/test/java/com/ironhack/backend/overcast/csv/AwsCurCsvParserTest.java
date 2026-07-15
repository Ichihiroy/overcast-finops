package com.ironhack.backend.overcast.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

class AwsCurCsvParserTest {

    private final UsageCsvParser parser = new UsageCsvParser();

    private static final String CUR = """
            lineItem/ResourceId,lineItem/ProductCode,lineItem/UsageType,lineItem/UsageAccountId,product/region,product/instanceType,lineItem/UsageAmount,lineItem/UnblendedRate,lineItem/UnblendedCost,lineItem/CurrencyCode,resourceTags/user:env,resourceTags/user:owner
            i-0abc,AmazonEC2,EUW2-BoxUsage:m5.large,111122223333,eu-west-2,m5.large,600,0.107,64.20,USD,dev,ana
            i-0abc,AmazonEC2,EUW2-EBS:VolumeUsage.gp3,111122223333,eu-west-2,,130,0.08,10.40,USD,dev,ana
            vol-9,AmazonEC2,EUW2-EBS:VolumeUsage.gp3,111122223333,eu-west-2,,730,0.08,58.40,USD,,
            snap-7,AmazonEC2,EUW2-EBS:SnapshotUsage,111122223333,eu-west-2,,50,0.05,2.50,USD,,
            eip-1,AmazonEC2,EUW2-ElasticIP:IdleAddress,111122223333,eu-west-2,,730,0.005,3.65,USD,,
            ,AmazonEC2,RIFee,111122223333,eu-west-2,,1,10.00,10.00,USD,,
            """;

    @Test
    void dispatchesOnCurHeadersAndAggregatesLineItems() {
        var result = parser.parse(new StringReader(CUR));

        assertThat(result.provider()).isEqualTo("aws");
        assertThat(result.currency()).isEqualTo("USD");
        // RIFee row has no resource id → dropped, 4 real resources remain
        assertThat(result.resources()).hasSize(4);

        NormalizedResource ec2 = byId(result.resources(), "i-0abc");
        assertThat(ec2.kind()).isEqualTo(ResourceKind.VM); // primary meter = BoxUsage
        assertThat(ec2.monthlyCost()).isEqualByComparingTo("74.60"); // 64.20 + 10.40
        assertThat(ec2.quantity()).isEqualByComparingTo("730");      // 600 + 130
        assertThat(ec2.sku()).isEqualTo("m5.large");
        assertThat(ec2.tags()).containsEntry("env", "dev").containsEntry("owner", "ana");

        assertThat(byId(result.resources(), "vol-9").kind()).isEqualTo(ResourceKind.DISK);
        assertThat(byId(result.resources(), "snap-7").kind()).isEqualTo(ResourceKind.SNAPSHOT);
        assertThat(byId(result.resources(), "eip-1").kind()).isEqualTo(ResourceKind.PUBLIC_IP);
    }

    @Test
    void flagsMissingEnrichmentColumnsLikeAzure() {
        var result = parser.parse(new StringReader(CUR));
        assertThat(result.hasAssociationColumn()).isFalse();
        assertThat(result.hasAgeColumn()).isFalse();
    }

    @Test
    void rejectsCurWithoutResourceIds() {
        String csv = """
                lineItem/ProductCode,lineItem/UnblendedCost
                AmazonEC2,10.00
                """;
        assertThatThrownBy(() -> parser.parse(new StringReader(csv)))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("resourceId");
    }

    @Test
    void monthlyServiceSummaryParsesWithSynthesizedResourceIds() {
        // Cost-Explorer-style monthly summary: snake_case headers, no resource
        // ids — each account/service/usage_type becomes one pseudo-resource.
        String csv = """
                invoice_month,linked_account_id,service,usage_type,region,usage_quantity,unit,unit_cost_usd,cost_usd
                2026-06,000000000001,Amazon EC2,BoxUsage:t3.medium,eu-west-1,730,Hrs,0.0456,33.29
                2026-06,000000000001,Amazon EC2,EBS:VolumeUsage.gp3,eu-west-1,500,GB-Mo,0.088,44.00
                2026-06,000000000001,Amazon RDS,InstanceUsage:db.t3.medium,eu-west-1,730,Hrs,0.072,52.56
                2026-06,000000000001,Amazon S3,TimedStorage-ByteHrs,eu-west-1,1200,GB-Mo,0.024,28.80
                """;
        var result = parser.parse(new StringReader(csv));

        assertThat(result.provider()).isEqualTo("aws");
        assertThat(result.resources()).hasSize(4);

        NormalizedResource ec2 = byId(result.resources(), "000000000001/Amazon EC2/BoxUsage:t3.medium");
        assertThat(ec2.kind()).isEqualTo(ResourceKind.VM);
        assertThat(ec2.quantity()).isEqualByComparingTo("730"); // sustained → RI candidate
        assertThat(ec2.sku()).isEqualTo("t3.medium");           // derived from the usage type
        assertThat(ec2.monthlyCost()).isEqualByComparingTo("33.29");

        // RDS instance hours are reservable compute; storage/S3 stay OTHER
        assertThat(byId(result.resources(), "000000000001/Amazon RDS/InstanceUsage:db.t3.medium").kind())
                .isEqualTo(ResourceKind.VM);
        assertThat(byId(result.resources(), "000000000001/Amazon S3/TimedStorage-ByteHrs").kind())
                .isEqualTo(ResourceKind.OTHER);
    }

    @Test
    void nonCurAwsExportGetsPointedAtTheCur() {
        // Cost Explorer-style download: bare headers, no lineItem/ namespace —
        // dispatches to the Azure parser, whose error must carry the AWS hint.
        String csv = """
                Service,Amount,Usage Type,Start,End
                AmazonEC2,42.00,BoxUsage,2026-06-01,2026-06-30
                """;
        assertThatThrownBy(() -> parser.parse(new StringReader(csv)))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("Cost and Usage Report")
                .hasMessageContaining("lineItem/ResourceId");
    }

    private static NormalizedResource byId(List<NormalizedResource> resources, String id) {
        return resources.stream().filter(r -> r.resourceId().equals(id)).findFirst().orElseThrow();
    }
}
