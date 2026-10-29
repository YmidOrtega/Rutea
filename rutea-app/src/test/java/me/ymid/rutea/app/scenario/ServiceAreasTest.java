package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.geo.AreaSpec;

class ServiceAreasTest {

    private static List<Locality> all() {
        List<Locality> all = new ArrayList<>();
        ServiceAreas.BOGOTA.forEach(area -> all.addAll(area.localities()));
        return all;
    }

    @Test
    void everyLocalityBelongsToExactlyOneArea() {
        assertThat(all()).extracting(Locality::number)
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(all()).extracting(Locality::osmRelation).doesNotHaveDuplicates();
        assertThat(ServiceAreas.BOGOTA).extracting(ServiceArea::number).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void subaMatchesTheRoadGraphArea() {
        Locality suba = ServiceAreas.locality(11).orElseThrow();

        assertThat(suba.osmRelation()).isEqualTo(AreaSpec.SUBA.localityRelation());
        assertThat(ServiceAreas.ASE_5.operation()).hasValueSatisfying(
                operation -> assertThat(operation.base().osmNode()).isEqualTo(site(AreaSpec.DEPOT)));
        assertThat(ServiceAreas.DISPOSAL_OSM_NODE).isEqualTo(site(AreaSpec.DISPOSAL));
    }

    @Test
    void onlyTheVerifiedAreaHasItsOperation() {
        assertThat(ServiceAreas.BOGOTA).filteredOn(area -> area.operation().isPresent())
                .containsExactly(ServiceAreas.ASE_5);
    }

    @Test
    void theAreasCoverTheWholeUrbanLand() {
        assertThat(ServiceAreas.BOGOTA.stream().mapToDouble(ServiceArea::urbanAreaShare).sum())
                .isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void anAreaWithoutItsOperationSaysWhatIsMissing() {
        assertThatThrownBy(() -> ServiceAreas.ASE_2.containerKgPerDay())
                .hasMessageContaining("ASE 2").hasMessageContaining("PENDIENTE_VERIFICAR");
        assertThat(ServiceAreas.ASE_2.nonRecyclableContainers()).isEqualTo(1_666);
    }

    @Test
    void namesAreaAndLocalitiesForFilesAndInstances() {
        assertThat(ServiceAreas.ASE_5.name()).isEqualTo("Suba");
        assertThat(ServiceAreas.ASE_3.name()).isEqualTo("ASE 3");
        assertThat(ServiceAreas.locality(17).orElseThrow().slug()).isEqualTo("la-candelaria");
        assertThat(ServiceAreas.locality(19).orElseThrow().slug()).isEqualTo("ciudad-bolivar");
    }

    @Test
    void splitsAnySelectionByArea() {
        Map<ServiceArea, List<Locality>> split = ServiceAreas.split(List.of(
                ServiceAreas.locality(11).orElseThrow(),
                ServiceAreas.locality(8).orElseThrow(),
                ServiceAreas.locality(1).orElseThrow(),
                ServiceAreas.locality(9).orElseThrow()));

        assertThat(split.keySet()).containsExactly(ServiceAreas.ASE_1, ServiceAreas.ASE_3, ServiceAreas.ASE_5);
        assertThat(split.get(ServiceAreas.ASE_3)).extracting(Locality::name).containsExactly("Kennedy", "Fontibón");
    }

    @Test
    void choosingEveryLocalityGivesTheFiveWholeAreas() {
        Map<ServiceArea, List<Locality>> split = ServiceAreas.split(all().reversed());

        assertThat(split.keySet()).containsExactlyElementsOf(ServiceAreas.BOGOTA);
        split.forEach((area, chosen) -> assertThat(chosen).isEqualTo(area.localities()));
    }

    @Test
    void rejectsAnEmptyOrForeignSelection() {
        assertThatThrownBy(() -> ServiceAreas.split(List.of())).hasMessageContaining("no hay localidades");
        assertThatThrownBy(() -> ServiceAreas.split(List.of(new Locality(11, "Suba", 1, false))))
                .hasMessageContaining("no es del catálogo");
    }

    private static long site(String name) {
        return AreaSpec.SUBA.sites().stream().filter(site -> site.name().equals(name)).findFirst().orElseThrow()
                .osmNode();
    }
}
