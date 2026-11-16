package com.nexora.corp.crm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProspectStageTest {

    @Test
    void transicionesDelEmbudo() {
        assertThat(ProspectStage.NEW.canMoveTo(ProspectStage.WON)).isFalse();
        assertThat(ProspectStage.QUALIFIED.canMoveTo(ProspectStage.WON)).isTrue();
        assertThat(ProspectStage.NEGOTIATION.canMoveTo(ProspectStage.CONTACTED)).isTrue();
        assertThat(ProspectStage.WON.canMoveTo(ProspectStage.LOST)).isFalse();
        assertThat(ProspectStage.LOST.canMoveTo(ProspectStage.NEGOTIATION)).isFalse();
        assertThat(ProspectStage.LOST.canMoveTo(ProspectStage.CONTACTED)).isTrue();
        assertThat(ProspectStage.DEMO_DONE.canMoveTo(ProspectStage.DEMO_DONE)).isFalse();
    }
}

