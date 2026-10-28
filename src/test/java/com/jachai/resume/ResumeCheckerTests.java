package com.jachai.resume;

import com.jachai.resume.service.ResumeChecker;
import com.jachai.resume.service.ResumeExtractor.Extracted;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResumeCheckerTests {
    private final ResumeChecker checker = new ResumeChecker();
    private final String resume = """
            Amina Rahman
            amina@example.com | +880 1712345678
            Experience
            Backend Developer, Example Company, 2022–2025
            Developed Java and Spring Boot services with SQL and Docker.
            Education
            BSc Computer Science, 2018–2022
            Skills
            Java, Spring Boot, SQL, Docker
            Projects
            Built a booking system with authenticated APIs and documented tests.
            """;

    private Extracted input(String text) { return new Extracted(text, 1, false, false, List.of()); }

    @Test void scoreIsExplainableAndIndependentOfTheJobDescription() {
        var a = checker.check(input(resume), "Java Spring Boot Python Docker");
        var b = checker.check(input(resume), "React Kubernetes");
        assertThat(a.readinessScore()).isEqualTo(b.readinessScore());
        assertThat(a.readinessScore()).isEqualTo(a.breakdown().stream().mapToInt(ResumeChecker.Check::earned).sum());
        assertThat(a.breakdown().stream().mapToInt(ResumeChecker.Check::possible).sum()).isEqualTo(100);
        assertThat(a.jobMatchScore()).isEqualTo(75);
        assertThat(a.missingSkills()).containsExactly("Python");
        assertThat(a.matchedSkills()).containsExactly("Java", "Spring Boot", "Docker");
    }

    @Test void doesNotInventAJobMatchOrMatchJavaInsideJavascript() {
        assertThat(checker.check(input(resume), "").jobMatchScore()).isNull();
        var report = checker.check(input("JavaScript developer\n" + resume.replace("Java", "Kotlin")), "Java and go to the office");
        assertThat(report.matchedSkills()).doesNotContain("Java", "Go");
        assertThat(report.missingSkills()).containsExactly("Java");
        assertThat(checker.check(input(resume), "Friendly colleague wanted").jobMatchScore()).isNull();
    }

    @Test void recognizesProjectsAsAnAlternativeForAnEntryLevelResume() {
        var report = checker.check(input(resume.replace("Experience", "Other work")), "");
        assertThat(report.issues()).noneMatch(i -> i.code().equals("EVIDENCE_SECTION_MISSING"));
    }

    @Test void recognizesInlineSkillsWithoutTreatingASentenceAsAHeading() {
        var report = checker.check(input(resume.replace("Skills\n", "Skills: ")), "");
        assertThat(report.issues()).noneMatch(i -> i.code().equals("SKILLS_MISSING"));
        var prose = checker.check(input(resume.replace("Skills\n", "My skills include ")), "");
        assertThat(prose.issues()).anyMatch(i -> i.code().equals("SKILLS_MISSING"));
    }

    @Test void reversedDatesAreFlaggedButOverlapIsNotDeclaredWrong() {
        assertThat(checker.check(input(resume.replace("2022–2025", "2025–2022")), "").issues())
                .anyMatch(i -> i.code().equals("DATES_REVIEW") && i.evidence().contains("2025–2022"));
        assertThat(checker.check(input(resume), "").issues()).noneMatch(i -> i.code().equals("DATES_REVIEW"));
    }

    @Test void unsupportedLanguageIsNotPunishedWithALowScore() {
        var report = checker.check(input("আমার জীবনবৃত্তান্ত এবং কাজের অভিজ্ঞতা শিক্ষাগত যোগ্যতা দক্ষতা".repeat(10)), "Java");
        assertThat(report.readinessScore()).isNull();
        assertThat(report.jobMatchScore()).isNull();
        assertThat(report.issues()).isEmpty();
    }

    @Test void tableAndOcrWarningsDoNotAutomaticallyDeductPoints() {
        var plain = checker.check(input(resume), "");
        var scanned = checker.check(new Extracted(resume, 1, true, true, List.of("OCR used")), "");
        assertThat(scanned.readinessScore()).isEqualTo(plain.readinessScore());
        assertThat(scanned.issues()).anyMatch(i -> i.code().equals("TABLE_REVIEW") && i.deduction() == 0);
        assertThat(scanned.warnings()).anyMatch(w -> w.contains("OCR errors"));
    }
}
