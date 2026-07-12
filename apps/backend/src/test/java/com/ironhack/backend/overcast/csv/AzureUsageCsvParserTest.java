package com.ironhack.backend.overcast.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

class AzureUsageCsvParserTest {

    private final AzureUsageCsvParser parser = new AzureUsageCsvParser();

    @Test
    void aggregatesMultipleMeterRowsForOneResource() {
        String csv = """
                ResourceId,ResourceType,ResourceGroup,ResourceLocation,MeterName,SKU,Quantity,UnitPrice,Cost,Currency,Tags,AssociatedResource,AgeDays
                /rg/vm1,Microsoft.Compute/virtualMachines,rg-prod,westeurope,Compute Hours,Standard_D4s_v5,600,0.48,288.00,EUR,"{""owner"":""x"",""env"":""prod""}",vm1-nic,
                /rg/vm1,Microsoft.Compute/virtualMachines,rg-prod,westeurope,Premium Storage,Standard_D4s_v5,130,0.48,62.40,EUR,"{""owner"":""x"",""env"":""prod""}",vm1-nic,
                """;
        var result = parser.parse(new StringReader(csv));

        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.resources()).hasSize(1);
        NormalizedResource vm = result.resources().get(0);
        assertThat(vm.kind()).isEqualTo(ResourceKind.VM);
        assertThat(vm.monthlyCost()).isEqualByComparingTo("350.40"); // 288.00 + 62.40
        assertThat(vm.quantity()).isEqualByComparingTo("730");       // 600 + 130
    }

    @Test
    void distinguishesKnownUnattachedFromUnknownAssociation() {
        String withColumn = """
                ResourceId,ResourceType,ResourceGroup,MeterName,SKU,Quantity,UnitPrice,Cost,Currency,Tags,AssociatedResource,AgeDays
                /rg/disk1,Microsoft.Compute/disks,rg-prod,Disk,P10,730,0.03,21.68,USD,"{}",,
                """;
        NormalizedResource disk = parser.parse(new StringReader(withColumn)).resources().get(0);
        // column present, value blank → known unattached (empty string, not null)
        assertThat(disk.associatedResource()).isEmpty();

        String withoutColumn = """
                ResourceId,ResourceType,ResourceGroup,MeterName,SKU,Quantity,UnitPrice,Cost,Currency,Tags
                /rg/disk1,Microsoft.Compute/disks,rg-prod,Disk,P10,730,0.03,21.68,USD,"{}"
                """;
        NormalizedResource disk2 = parser.parse(new StringReader(withoutColumn)).resources().get(0);
        // column absent → association unknown (null): unattached rule must stay silent
        assertThat(disk2.associatedResource()).isNull();
    }

    @Test
    void flagsWhetherEnrichmentColumnsArePresent() {
        String enriched = """
                ResourceId,ResourceType,ResourceGroup,MeterName,SKU,Quantity,UnitPrice,Cost,Currency,Tags,AssociatedResource,AgeDays
                /rg/disk1,Microsoft.Compute/disks,rg-prod,Disk,P10,730,0.03,21.68,USD,"{}",,
                """;
        var enrichedResult = parser.parse(new StringReader(enriched));
        assertThat(enrichedResult.hasAssociationColumn()).isTrue();
        assertThat(enrichedResult.hasAgeColumn()).isTrue();

        // A raw Azure Cost Management export carries neither enrichment column —
        // this is what drives the "raw export" data-quality warning.
        String raw = """
                ResourceId,ResourceType,ResourceGroup,MeterName,SKU,Quantity,UnitPrice,Cost,Currency,Tags
                /rg/disk1,Microsoft.Compute/disks,rg-prod,Disk,P10,730,0.03,21.68,USD,"{}"
                """;
        var rawResult = parser.parse(new StringReader(raw));
        assertThat(rawResult.hasAssociationColumn()).isFalse();
        assertThat(rawResult.hasAgeColumn()).isFalse();
    }

    @Test
    void handlesAliasedHeadersAndQuotedTagsWithCommas() {
        String csv = """
                InstanceId,ConsumedService,ResourceGroupName,MeterName,MeterSubCategory,UsageQuantity,EffectivePrice,CostInBillingCurrency,BillingCurrency,Tags
                /rg/x,Microsoft.Compute/virtualMachines,rg-dev,Hours,Standard_D2s_v5,730,0.12,87.60,USD,"{""owner"":""ana"",""team"":""a,b,c""}"
                """;
        var result = parser.parse(new StringReader(csv));
        assertThat(result.resources()).hasSize(1);
        NormalizedResource vm = result.resources().get(0);
        assertThat(vm.resourceGroup()).isEqualTo("rg-dev");
        assertThat(vm.tags()).containsEntry("team", "a,b,c").containsEntry("owner", "ana");
    }

    @Test
    void rejectsCsvMissingRequiredColumns() {
        String csv = """
                ResourceId,ResourceGroup,Cost
                /rg/x,rg-prod,10.00
                """;
        assertThatThrownBy(() -> parser.parse(new StringReader(csv)))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("resourceType");
    }

    @Test
    void classifiesAzureResourceTypes() {
        assertThat(ResourceKind.fromAzureType("Microsoft.Compute/virtualMachines")).isEqualTo(ResourceKind.VM);
        assertThat(ResourceKind.fromAzureType("Microsoft.Compute/disks")).isEqualTo(ResourceKind.DISK);
        assertThat(ResourceKind.fromAzureType("Microsoft.Compute/snapshots")).isEqualTo(ResourceKind.SNAPSHOT);
        assertThat(ResourceKind.fromAzureType("Microsoft.Network/publicIPAddresses")).isEqualTo(ResourceKind.PUBLIC_IP);
        assertThat(ResourceKind.fromAzureType("Microsoft.Sql/servers/databases")).isEqualTo(ResourceKind.OTHER);
    }

    @Test
    void parsesEmbeddedNewlinesInsideQuotedFields() {
        List<List<String>> rows;
        try {
            rows = CsvReader.parse(new StringReader("a,b\n\"line1\nline2\",second\n"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(0)).isEqualTo("line1\nline2");
        assertThat(rows.get(1).get(1)).isEqualTo("second");
    }
}
