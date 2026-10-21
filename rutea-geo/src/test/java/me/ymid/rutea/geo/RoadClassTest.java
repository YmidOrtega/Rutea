package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RoadClassTest {

    @Test
    void mapsLinksToTheirParentClass() {
        assertThat(RoadClass.of(Map.of("highway", "primary_link"))).contains(RoadClass.PRIMARY);
        assertThat(RoadClass.of(Map.of("highway", "motorway_link"))).contains(RoadClass.MOTORWAY);
    }

    @Test
    void ignoresWaysTrucksCannotUse() {
        assertThat(RoadClass.of(Map.of("highway", "footway"))).isEmpty();
        assertThat(RoadClass.of(Map.of("highway", "cycleway"))).isEmpty();
        assertThat(RoadClass.of(Map.of("highway", "construction"))).isEmpty();
        assertThat(RoadClass.of(Map.of("highway", "pedestrian", "area", "yes"))).isEmpty();
        assertThat(RoadClass.of(Map.of("highway", "residential", "access", "private"))).isEmpty();
        assertThat(RoadClass.of(Map.of("highway", "residential", "hgv", "no"))).isEmpty();
        assertThat(RoadClass.of(Map.of("building", "yes"))).isEmpty();
    }

    @Test
    void admitsPrivateRoadsOnlyWhenAskedButNeverForbiddenOnes() {
        Map<String, String> landfillRoad = Map.of("highway", "service", "access", "private");

        assertThat(RoadClass.of(landfillRoad)).isEmpty();
        assertThat(RoadClass.ofAnyAccess(landfillRoad)).contains(RoadClass.SERVICE);
        assertThat(RoadClass.isPrivate(landfillRoad)).isTrue();
        assertThat(RoadClass.ofAnyAccess(Map.of("highway", "service", "access", "no"))).isEmpty();
    }

    @Test
    void separatesArterialsFromLocalStreets() {
        assertThat(RoadClass.SECONDARY.isArterial()).isTrue();
        assertThat(RoadClass.TERTIARY.isArterial()).isFalse();
        assertThat(RoadClass.LIVING_STREET.isPublicStreet()).isTrue();
        assertThat(RoadClass.SERVICE.isPublicStreet()).isFalse();
    }

    @Test
    void usesMaxspeedOnlyWhenItIsLower() {
        assertThat(RoadClass.PRIMARY.kmPerHour(Map.of())).isEqualTo(40);
        assertThat(RoadClass.PRIMARY.kmPerHour(Map.of("maxspeed", "30"))).isEqualTo(30);
        assertThat(RoadClass.PRIMARY.kmPerHour(Map.of("maxspeed", "60"))).isEqualTo(40);
        assertThat(RoadClass.PRIMARY.kmPerHour(Map.of("maxspeed", "20 mph"))).isEqualTo(32);
        assertThat(RoadClass.PRIMARY.kmPerHour(Map.of("maxspeed", "CO:urban"))).isEqualTo(40);
        assertThat(RoadClass.PRIMARY.kmPerHour(Map.of("maxspeed", "0"))).isEqualTo(40);
    }

    @Test
    void readsOneWayTagsAndWhatTheyImply() {
        assertThat(Direction.of(Map.of())).isEqualTo(Direction.BOTH);
        assertThat(Direction.of(Map.of("oneway", "yes"))).isEqualTo(Direction.FORWARD);
        assertThat(Direction.of(Map.of("oneway", "-1"))).isEqualTo(Direction.BACKWARD);
        assertThat(Direction.of(Map.of("junction", "roundabout"))).isEqualTo(Direction.FORWARD);
        assertThat(Direction.of(Map.of("junction", "roundabout", "oneway", "no"))).isEqualTo(Direction.BOTH);
        assertThat(Direction.of(Map.of("highway", "motorway"))).isEqualTo(Direction.FORWARD);
        assertThat(Direction.of(Map.of("oneway", "reversible"))).isEqualTo(Direction.NONE);
    }
}
