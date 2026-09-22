package com.eveningoutpost.dexdrip;

import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

public class NoteSearchTest {

    @Test
    public void normalizeSearchTerm_removesDiacriticsAndIgnoresCase() {
        assertThat(NoteSearch.normalizeSearchTerm("Cafe Șnițel ÀÂÎĂ"))
                .isEqualTo("cafe snitel aaia");
    }

    @Test
    public void normalizeSearchTerm_allowsPlainTextSearchForDiacriticNotes() {
        assertThat(NoteSearch.normalizeSearchTerm("mâncare după prânz")
                .contains(NoteSearch.normalizeSearchTerm("mancare dupa pranz"))).isTrue();
    }
}
