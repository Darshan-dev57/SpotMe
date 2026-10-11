package dev.darshan.spotme.drive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriveLinksTest {

    @Test
    void findsTheFolderIdInTheLinksPeoplePaste() {
        String id = "1urzpnHbKfLVay_WEgbd1KSLrYp6iga9h";
        assertThat(DriveLinks.folderId("https://drive.google.com/drive/folders/" + id)).isEqualTo(id);
        assertThat(DriveLinks.folderId("https://drive.google.com/drive/folders/" + id + "?usp=sharing")).isEqualTo(id);
        assertThat(DriveLinks.folderId("  https://drive.google.com/drive/u/1/folders/" + id + "  ")).isEqualTo(id);
        assertThat(DriveLinks.folderId("https://drive.google.com/open?id=" + id)).isEqualTo(id);
    }

    @Test
    void refusesOtherSitesFilesAndPlainHttp() {
        assertThat(DriveLinks.folderId("https://evil.example/drive/folders/1urzpnHbKfLVay_WEgbd1KSLrYp6iga9h")).isNull();
        assertThat(DriveLinks.folderId("http://drive.google.com/drive/folders/1urzpnHbKfLVay_WEgbd1KSLrYp6iga9h")).isNull();
        assertThat(DriveLinks.folderId("https://drive.google.com.evil.example/drive/folders/1urzpnHbKfLVay_WEgbd1KSLrYp6iga9h")).isNull();
        assertThat(DriveLinks.folderId("https://drive.google.com/file/d/1p6ulmIk9mXiPMQOB8lH64yjHKaD319Pr/view")).isNull();
        assertThat(DriveLinks.folderId("not a link")).isNull();
        assertThat(DriveLinks.folderId(null)).isNull();
    }
}
