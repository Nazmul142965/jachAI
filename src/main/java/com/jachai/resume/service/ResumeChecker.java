package com.jachai.resume.service;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Versioned, deterministic checks. Evidence comes from the document, never from an AI verdict. */
@Component
public class ResumeChecker {
    public static final String VERSION = "resume-rules-1";
    public record Issue(String code, String severity, String title, String evidence, String action, int deduction) { }
    public record Check(String label, int earned, int possible) { }
    public record Report(String engineVersion, Integer readinessScore, Integer jobMatchScore,
                         String extractedText, int wordCount, int pages, boolean ocr,
                         List<Check> breakdown, List<Issue> issues, List<String> matchedSkills,
                         List<String> missingSkills, List<String> warnings) { }

    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\+?\\d[\\d ()-]{7,20}\\d(?!\\d)");
    private static final Pattern YEARS = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\s*[-–—]\\s*(19\\d{2}|20\\d{2})\\b");
    private static final Map<String, List<String>> SKILLS = skills();

    public Report check(ResumeExtractor.Extracted extracted, String jobDescription) {
        String text = extracted.text();
        List<Issue> issues = new ArrayList<>();
        List<Check> breakdown = new ArrayList<>();
        List<String> warnings = new ArrayList<>(extracted.warnings());
        int words = text.strip().split("\\s+").length;
        long letters = text.codePoints().filter(Character::isLetter).count();
        long latin = text.codePoints().filter(c -> Character.isLetter(c)
                && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN).count();
        boolean supported = letters > 0 && latin * 100 / letters >= 80;
        if (!supported) {
            warnings.add("Scoring currently supports English resumes only. No readiness or job-match score was assigned.");
            return new Report(VERSION, null, null, text, words, extracted.pages(), extracted.ocr(),
                    List.of(), List.of(), List.of(), List.of(), warnings);
        }

        rule(breakdown, issues, EMAIL.matcher(text).find(), "Contact email", 10, "EMAIL_MISSING",
                "Email was not found", "No email address was found in the extracted text.",
                "Add a contact email in the document body. If it is already present, check the extracted text.");
        rule(breakdown, issues, hasPhone(text), "Contact phone", 5, "PHONE_MISSING",
                "Phone number was not found", "No likely phone number was found in the extracted text.",
                "Add a phone number if you want employers to contact you by phone.");
        boolean experience = heading(text, "(?:work\\s+|professional\\s+|employment\\s+)?experience|employment(?:\\s+history)?|work\\s+history|internships?");
        boolean projects = heading(text, "(?:(?:personal|academic|selected|technical)\\s+)?projects?");
        rule(breakdown, issues, experience || projects, "Experience or projects", 25, "EVIDENCE_SECTION_MISSING",
                "Experience or projects section was not found", "No recognized experience or project heading was found.",
                "Use a clear Experience or Projects heading and describe work you actually completed. Projects are suitable for a first resume.");
        rule(breakdown, issues, heading(text, "education|academic(?:\\s+(?:background|qualifications))?|qualifications"),
                "Education section", 10, "EDUCATION_MISSING", "Education heading was not found",
                "No recognized education heading was found.", "Use a clear Education heading for your relevant education or training.");
        rule(breakdown, issues, heading(text, "(?:(?:technical|core|key|professional)\\s+)?skills|technical\\s+proficienc(?:y|ies)|competencies"),
                "Skills section", 10, "SKILLS_MISSING", "Skills heading was not found",
                "No recognized skills heading was found.", "Add a Skills heading and list skills you can demonstrate.");
        var dateMatcher = YEARS.matcher(text);
        String reversed = null;
        while (dateMatcher.find()) {
            if (Integer.parseInt(dateMatcher.group(1)) > Integer.parseInt(dateMatcher.group(2))) {
                reversed = dateMatcher.group(); break;
            }
        }
        boolean dates = Pattern.compile("\\b(?:19\\d{2}|20\\d{2})\\b").matcher(text).find();
        rule(breakdown, issues, dates && reversed == null, "Year dates", 15, "DATES_REVIEW",
                "Review your dates", reversed == null ? "No four-digit year was found." : "This date range ends before it starts: " + reversed,
                "Check education and work dates. Use clear month/year dates; overlapping jobs are not automatically an error.");
        rule(breakdown, issues, words >= 150 && words <= 1500, "Text length", 5, "LENGTH_REVIEW",
                "Review resume length", "The extracted text contains " + words + " words.",
                "This checker uses a 150–1500 word guideline. Keep relevant detail; this is not an employer requirement.");
        var counts = new LinkedHashMap<String, Integer>();
        text.lines().map(String::strip).filter(line -> line.length() >= 45)
                .forEach(line -> counts.merge(line.toLowerCase(Locale.ROOT), 1, Integer::sum));
        String duplicate = counts.entrySet().stream().filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        rule(breakdown, issues, duplicate == null, "Repeated text", 5, "DUPLICATE_TEXT",
                "Repeated text needs review", duplicate == null ? "" : duplicate.substring(0, Math.min(160, duplicate.length())),
                "Remove accidental repetitions. Keep repeated wording when it is necessary and accurate.");
        long damaged = text.chars().filter(c -> c == '\uFFFD' || c == '\u25A1').count();
        rule(breakdown, issues, damaged <= 3, "Readable characters", 15, "CHARACTERS_REVIEW",
                "Some characters may not have been read", damaged + " replacement or box characters were extracted.",
                "Check the text preview. Export with embedded fonts or upload a clearer document.");
        if (extracted.tables()) issues.add(new Issue("TABLE_REVIEW", "REVIEW", "Check the reading order",
                "A table was found in the DOCX body.",
                "Check the text preview for mixed-up columns. A table alone does not mean an ATS will reject the resume.", 0));
        if (extracted.pages() > 2) issues.add(new Issue("PAGE_COUNT", "SUGGESTION", "Check that every page is relevant",
                "The PDF contains " + extracted.pages() + " pages.", "Keep the length appropriate for your role and experience. No points were deducted.", 0));

        List<String> matched = new ArrayList<>(), missing = new ArrayList<>();
        Integer matchScore = null;
        if (jobDescription != null && !jobDescription.isBlank()) {
            for (var skill : SKILLS.entrySet()) {
                if (mentions(jobDescription, skill.getValue())) {
                    (mentions(text, skill.getValue()) ? matched : missing).add(skill.getKey());
                }
            }
            int total = matched.size() + missing.size();
            if (total > 0) matchScore = (int) Math.round(matched.size() * 100.0 / total);
            else warnings.add("No supported skill keywords were recognized in this job description. No job-match score was assigned.");
            warnings.add("Job match measures explicit mentions from a limited technical skills dictionary, equally weighted. It does not verify proficiency, seniority, required versus preferred skills, or hiring eligibility; negated mentions need manual review.");
        }
        warnings.add("This is a JachAI rules-based estimate, not an employer ATS result or a hiring prediction. Unrecognized headings can affect the score. Visual layout and the truth of claims were not verified.");
        if (extracted.ocr()) warnings.add("OCR errors can affect all checks and scores; confirm the extracted text first.");
        return new Report(VERSION, breakdown.stream().mapToInt(Check::earned).sum(), matchScore,
                text, words, extracted.pages(), extracted.ocr(), List.copyOf(breakdown), List.copyOf(issues),
                List.copyOf(matched), List.copyOf(missing), List.copyOf(warnings));
    }

    private void rule(List<Check> checks, List<Issue> issues, boolean passed, String label, int points,
                      String code, String title, String evidence, String action) {
        checks.add(new Check(label, passed ? points : 0, points));
        if (!passed) issues.add(new Issue(code, "REVIEW", title, evidence, action, points));
    }

    private boolean heading(String text, String names) {
        return Pattern.compile("(?im)^\\h*(?:" + names + ")(?:\\h*[:|]\\h*[^\\r\\n]*|\\h*[–—-]?\\h*)$").matcher(text).find();
    }

    private boolean hasPhone(String text) {
        var matcher = PHONE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            int digits = candidate.replaceAll("\\D", "").length();
            if (digits >= 10 && digits <= 15 && !YEARS.matcher(candidate).find()) return true;
        }
        return false;
    }

    private boolean mentions(String text, List<String> aliases) {
        return aliases.stream().anyMatch(alias -> Pattern.compile("(?i)(?<![\\p{L}\\p{N}])"
                + Pattern.quote(alias) + "(?![\\p{L}\\p{N}+#])").matcher(text).find());
    }

    private static Map<String, List<String>> skills() {
        var skills = new LinkedHashMap<String, List<String>>();
        for (String name : List.of("Java", "Python", "JavaScript", "TypeScript", "C++", "C#", "PHP", "Ruby", "Kotlin", "Swift",
                "HTML", "CSS", "SQL", "PostgreSQL", "MongoDB", "MySQL", "Redis", "React", "Angular", "Vue", "Spring Boot",
                "Django", "Flask", "FastAPI", "Docker", "Kubernetes", "Terraform", "AWS", "Azure", "Git", "Linux", "GraphQL",
                "JUnit", "Selenium", "Playwright", "Figma", "Excel", "Tableau", "Power BI", "TensorFlow", "PyTorch",
                "Agile", "Scrum", "Jira", "Kafka", "RabbitMQ", "Jenkins", "Maven", "Gradle", "Elasticsearch"))
            skills.put(name, List.of(name));
        skills.put("Node.js", List.of("Node.js", "NodeJS", "Node JS"));
        skills.put("REST API", List.of("REST", "RESTful", "REST API"));
        skills.put("CI/CD", List.of("CI/CD", "continuous integration", "continuous delivery"));
        skills.put("Go", List.of("Golang")); // "go" in ordinary prose is not evidence of the language.
        skills.put(".NET", List.of(".NET", "dotnet"));
        return Collections.unmodifiableMap(skills);
    }
}
