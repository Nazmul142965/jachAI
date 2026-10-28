package com.jachai.resume.service;

import com.jachai.shared.error.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

@Component
public class ResumeExtractor {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT = 80_000;
    private final String tesseract;
    private final boolean ocrEnabled;

    public ResumeExtractor(@Value("${app.resume.ocr-command:tesseract}") String tesseract,
                           @Value("${app.resume.ocr-enabled:false}") boolean ocrEnabled) {
        this.tesseract = tesseract;
        this.ocrEnabled = ocrEnabled;
    }

    public record Extracted(String text, int pages, boolean ocr, boolean tables, List<String> warnings) { }

    public Extracted extract(byte[] bytes, String fileName) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("Upload a PDF or DOCX no larger than 5 MB.");
        try {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf") && starts(bytes, "%PDF-")) return pdf(bytes);
            if (lower.endsWith(".docx") && bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K') return docx(bytes);
            throw invalid("The file contents must match a PDF or DOCX file.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("This resume could not be read. Upload an unlocked, valid PDF or DOCX.");
        }
    }

    private Extracted pdf(byte[] bytes) throws Exception {
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent())
                throw invalid("Upload a PDF without password or extraction restrictions.");
            int pages = pdf.getNumberOfPages();
            if (pages < 1 || pages > 10) throw invalid("Upload a resume with 1 to 10 pages.");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> warnings = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            boolean usedOcr = false;
            var renderer = new PDFRenderer(pdf);
            for (int i = 0; i < pages; i++) {
                stripper.setStartPage(i + 1); stripper.setEndPage(i + 1);
                String pageText = stripper.getText(pdf);
                if (pageText.codePoints().filter(Character::isLetterOrDigit).count() < 30) {
                    if (!ocrEnabled) throw invalid("This PDF needs OCR. OCR is not enabled on this server; upload a text-based PDF or DOCX.");
                    // Cap rendered pixels before allocating the page image.
                    var box = pdf.getPage(i).getCropBox();
                    if (box.getWidth() <= 0 || box.getHeight() <= 0
                            || box.getWidth() * box.getHeight() * 4 > 8_000_000)
                        throw invalid("A page is too large to scan safely. Export it at a standard page size.");
                    pageText = ocr(renderer, i); usedOcr = true;
                }
                text.append(pageText).append('\n');
                if (text.length() > MAX_TEXT) throw invalid("The resume contains too much text. Use a shorter document.");
            }
            if (usedOcr) warnings.add("OCR was used. Check the extracted text before relying on this report.");
            return checked(text.toString(), pages, usedOcr, false, warnings);
        }
    }

    private String ocr(PDFRenderer renderer, int page) throws Exception {
        Path dir = Files.createTempDirectory("jachai-resume-");
        Path image = dir.resolve("page.png"), output = dir.resolve("result.txt");
        Process process = null;
        try {
            ImageIO.write(renderer.renderImageWithDPI(page, 144), "png", image.toFile());
            process = new ProcessBuilder(tesseract, image.toString(), dir.resolve("result").toString(),
                    "-l", "eng", "--psm", "3").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw invalid("OCR took too long. Upload a text-based PDF or DOCX.");
            if (process.exitValue() != 0 || !Files.exists(output)) throw invalid("OCR could not read this page. Upload a clearer document.");
            if (Files.size(output) > MAX_TEXT) throw invalid("The scanned page contains too much text.");
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(image); Files.deleteIfExists(output); Files.deleteIfExists(dir);
        }
    }

    private Extracted docx(byte[] bytes) throws Exception {
        byte[] document = null;
        int entries = 0, total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 200) throw invalid("This DOCX contains too many embedded parts.");
                byte[] content = zip.readNBytes(2 * 1024 * 1024 + 1);
                total += content.length;
                if (content.length > 2 * 1024 * 1024 || total > 15 * 1024 * 1024)
                    throw invalid("This DOCX expands beyond the processing limit.");
                if (entry.getName().equals("word/document.xml")) {
                    if (document != null) throw invalid("This DOCX contains duplicate document parts.");
                    document = content;
                }
            }
        }
        if (document == null) throw invalid("This file is not a valid Word DOCX document.");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute("jdk.xml.maxElementDepth", "100");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
        String ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        if (!ns.equals(xml.getDocumentElement().getNamespaceURI())) throw invalid("Unsupported DOCX document format.");
        StringBuilder text = new StringBuilder();
        walk(xml.getDocumentElement(), text);
        boolean tables = xml.getElementsByTagNameNS(ns, "tbl").getLength() > 0;
        return checked(text.toString(), 0, false, tables,
                List.of("DOCX body text was checked. Headers, footers, images and visual layout were not evaluated."));
    }

    private void walk(Node node, StringBuilder text) {
        if ("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(node.getNamespaceURI())) {
            if ("t".equals(node.getLocalName())) text.append(node.getTextContent());
            if (Set.of("tab", "br").contains(node.getLocalName())) text.append(' ');
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) walk(child, text);
        if ("p".equals(node.getLocalName())) text.append('\n');
        if (text.length() > MAX_TEXT) throw invalid("The resume contains too much text. Use a shorter document.");
    }

    private Extracted checked(String text, int pages, boolean ocr, boolean tables, List<String> warnings) {
        text = text.replace("\u0000", "").strip();
        if (text.codePoints().filter(Character::isLetterOrDigit).count() < 80)
            throw invalid("Not enough readable text for a fair score. Upload a clearer resume or a text-based document.");
        return new Extracted(text, pages, ocr, tables, warnings);
    }

    private boolean starts(byte[] bytes, String prefix) {
        return bytes.length >= prefix.length()
                && new String(bytes, 0, prefix.length(), StandardCharsets.US_ASCII).equals(prefix);
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESUME_UNREADABLE", message);
    }
}
