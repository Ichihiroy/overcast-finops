package com.ironhack.backend.overcast.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ironhack.backend.overcast.domain.NormalizedResource;
import com.ironhack.backend.overcast.domain.ResourceKind;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

class GcpBillingCsvParserTest {

    private final UsageCsvParser parser = new UsageCsvParser();

    private static final String GCP = """
            service.description,sku.description,project.id,location.region,resource.name,usage.amount,cost,currency,labels
            Compute Engine,N1 Instance Core running in EMEA,proj-prod,europe-west1,vm-web-1,600,45.10,USD,"[{""key"":""env"",""value"":""prod""},{""key"":""owner"",""value"":""ana""}]"
            Compute Engine,N1 Instance Ram running in EMEA,proj-prod,europe-west1,vm-web-1,2400,12.30,USD,
            Compute Engine,Storage PD Capacity,proj-prod,europe-west1,disk-data-9,730,20.00,USD,
            Compute Engine,Storage PD Snapshot,proj-prod,europe-west1,snap-old-7,50,2.50,USD,
            Compute Engine,Static Ip Charge on a Standard VM,proj-prod,europe-west1,ip-idle-1,730,7.30,USD,
            Cloud Logging,Log Storage cost,proj-prod,europe-west1,,1,3.00,USD,
            """;

    @Test
    void dispatchesOnDottedHeadersAndAggregates() {
        var result = parser.parse(new StringReader(GCP));

        assertThat(result.provider()).isEqualTo("gcp");
        assertThat(result.currency()).isEqualTo("USD");
        // Cloud Logging row has no resource.name → dropped, 4 resources remain
        assertThat(result.resources()).hasSize(4);

        NormalizedResource vm = byId(result.resources(), "vm-web-1");
        assertThat(vm.kind()).isEqualTo(ResourceKind.VM);
        assertThat(vm.monthlyCost()).isEqualByComparingTo("57.40"); // 45.10 + 12.30
        assertThat(vm.resourceGroup()).isEqualTo("proj-prod");
        assertThat(vm.tags()).containsEntry("env", "prod").containsEntry("owner", "ana");

        assertThat(byId(result.resources(), "disk-data-9").kind()).isEqualTo(ResourceKind.DISK);
        assertThat(byId(result.resources(), "snap-old-7").kind()).isEqualTo(ResourceKind.SNAPSHOT);
        assertThat(byId(result.resources(), "ip-idle-1").kind()).isEqualTo(ResourceKind.PUBLIC_IP);
    }

    @Test
    void providerSelectorOverridesDetection() {
        // Forcing "gcp" must route straight to the GCP parser…
        var result = parser.parse(new StringReader(GCP), "gcp");
        assertThat(result.provider()).isEqualTo("gcp");

        // …and forcing "aws" on the same file must fail with the CUR error,
        // not fall back to another parser.
        assertThatThrownBy(() -> parser.parse(new StringReader(GCP), "aws"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("CUR");
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> parser.parse(new StringReader(GCP), "oracle"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("azure, aws, or gcp");
    }

    @Test
    void rejectsExportWithoutResourceNames() {
        String csv = """
                service.description,sku.description,cost,currency
                Compute Engine,N1 Instance Core,10.00,USD
                """;
        assertThatThrownBy(() -> parser.parse(new StringReader(csv)))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("resource");
    }

    private static NormalizedResource byId(List<NormalizedResource> resources, String id) {
        return resources.stream().filter(r -> r.resourceId().equals(id)).findFirst().orElseThrow();
    }
}
