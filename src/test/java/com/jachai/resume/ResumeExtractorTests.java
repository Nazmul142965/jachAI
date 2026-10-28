package com.jachai.resume;

import com.jachai.resume.service.ResumeExtractor;
import com.jachai.shared.error.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResumeExtractorTests {
    private final ResumeExtractor extractor = new ResumeExtractor("not-installed", false);
    private final String ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final String content = "Amina Rahman amina@example.com Java developer with experience building reliable services and maintaining production systems since 2022.";

    private byte[] docx(String xml) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test void readsRealPdfText() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(20, 700); stream.showText(content); stream.endText();
            }
            pdf.save(out);
        }
        assertThat(extractor.extract(out.toByteArray(), "Resume.PDF").text()).contains(content);
    }

    @Test void readsDocxBodyAndDetectsTables() throws Exception {
        byte[] file = docx("<w:document xmlns:w='" + ns + "'><w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>"
                + content + "</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>");
        var result = extractor.extract(file, "resume.docx");
        assertThat(result.text()).contains(content);
        assertThat(result.tables()).isTrue();
    }

    @Test void rejectsSpoofedEmptyOrOversizeFiles() {
        assertThatThrownBy(() -> extractor.extract(content.getBytes(), "resume.pdf")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> extractor.extract(new byte[0], "resume.pdf")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> extractor.extract(new byte[ResumeExtractor.MAX_BYTES + 1], "resume.docx")).isInstanceOf(ApiException.class);
    }

    @Test void refusesExternalXmlEntitiesAndZipExpansion() throws Exception {
        byte[] xxe = docx("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///not-readable'>]><w:document xmlns:w='" + ns + "'><w:body>&leak;</w:body></w:document>");
        assertThatThrownBy(() -> extractor.extract(xxe, "resume.docx")).isInstanceOf(ApiException.class);
        byte[] huge = docx("x".repeat(2 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> extractor.extract(huge, "resume.docx")).isInstanceOf(ApiException.class);
    }

    @Test void scannedPdfWithoutOcrExplainsHowToProceed() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(out); }
        assertThatThrownBy(() -> extractor.extract(out.toByteArray(), "scan.pdf"))
                .isInstanceOf(ApiException.class).hasMessageContaining("needs OCR");
    }
}
