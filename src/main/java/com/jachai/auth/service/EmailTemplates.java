package com.jachai.auth.service;

import com.jachai.mail.model.EmailPayload;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds every email the platform sends, as a designed HTML page plus a plain-text twin.
 *
 * Mail clients are old and picky, so the layout is tables with inline styles, no scripts and no web fonts.
 * Everything that comes from a person (names, titles) is escaped before it reaches the HTML.
 */
final class EmailTemplates {
    private static final String BRAND = "#4f46e5";
    private static final String ACCENT = "#06b6d4";

    record Rendered(String subject, String text, String html) { }

    private record Content(String subject, String preheader, String icon, String heading,
                           List<String> paragraphs, String detail, String button, String note) { }

    private EmailTemplates() { }

    static Rendered render(EmailPayload payload) {
        Content content = content(payload);
        String name = clean(payload.displayName());
        String url = payload.url();
        return new Rendered(content.subject(), text(content, name, url), html(content, name, url));
    }

    private static Content content(EmailPayload p) {
        String title = clean(p.campaignTitle());
        String who = clean(p.displayName());
        String lines = p.campaignTitle() == null ? "" : p.campaignTitle().replace("\r\n", "\n").trim();
        return switch (p.type()) {
            case VERIFICATION -> new Content("Verify your JachAI email", "One tap to confirm your email address.",
                    "✉", "Confirm your email",
                    List.of("Welcome to JachAI! Please confirm that this email address is yours."),
                    null, "Verify my email", "This link works for 24 hours. If you did not create an account, you can ignore this email.");
            case PASSWORD_RESET -> new Content("Reset your JachAI password", "Choose a new password for your account.",
                    "🔑", "Choose a new password",
                    List.of("We got a request to reset the password for your JachAI account."),
                    null, "Choose a new password", "This link works for 30 minutes. If you did not ask for it, ignore this email. Your password stays the same.");
            case ASSESSMENT_INVITATION -> new Content("Your JachAI assessment invitation", who + " invited you to take a test.",
                    "📝", "You are invited to a test",
                    List.of(who + " invited you to the assessment campaign below.",
                            "Open the link, sign in or register with this email address, and accept the invitation."),
                    title, "Open my invitation", "Did not expect this? You can ignore this email.");
            case ORGANIZATION_INVITATION -> new Content("Join " + who + " on JachAI", "You were invited to join a workspace.",
                    "🏫", "Join " + who,
                    List.of("You have been invited to join " + who + " as " + title.replace('_', ' ').toLowerCase() + ".",
                            "Open the link, sign in or register with this email address, and accept the invitation."),
                    null, "Accept the invitation", "Did not expect this? You can ignore this email.");
            case CERTIFICATE_ISSUED -> new Content("Your JachAI certificate is ready", "Your certificate has been issued.",
                    "🎓", "Your certificate is ready",
                    List.of("Congratulations! Your certificate has been issued.",
                            "You can view it, download it and share the link so anyone can check that it is real."),
                    null, "View my certificate", "Did not expect this message? Please contact your institute.");
            case RESULT_RELEASED -> new Content("Your JachAI assessment result is available", "Your result is ready to read.",
                    "📊", "Your result is ready",
                    List.of("Your assessment result is now available.",
                            "Sign in and open “My assessments” to see your score and answers."),
                    null, "See my result", "Did not expect this message? Please contact your organization.");
            case FEEDBACK_PUBLISHED -> new Content("Your JachAI instructor feedback is available", "Your instructor left feedback for you.",
                    "💬", "You have new feedback",
                    List.of("Your instructor has written feedback on your assessment.",
                            "Sign in and open “My assessments” to read it."),
                    null, "Read the feedback", "Did not expect this message? Please contact your institute.");
            case ASSIGNMENT_REMINDER -> new Content("Reminder: your JachAI assessment", "A test is waiting for you.",
                    "⏰", "Your test is waiting",
                    List.of("This assessment is waiting for you. Please finish it before the time window ends."),
                    title, "Go to my assessments", "Already finished it? Then you do not need to do anything.");
            case BILLING_NOTICE -> new Content("Your JachAI payment receipt", "Thank you. Your payment was received.",
                    "🧾", "Payment received",
                    List.of("Thank you! Your payment was received and your plan is now active."),
                    lines, "See my plan", "This receipt is for a demo payment. No real money was charged.");
            case SUPPORT_TICKET_UPDATE -> new Content("Update on your JachAI support ticket", "There is news on your support ticket.",
                    "🛟", "Your support ticket was updated",
                    List.of("There is an update on your support ticket. Sign in to read it and reply."),
                    title, "Open my ticket", "Did not expect this message? You can ignore it.");
        };
    }

    private static String html(Content c, String name, String url) {
        StringBuilder body = new StringBuilder();
        if (!name.isBlank() && !name.equals("there")) {
            body.append(paragraph("Hello " + esc(name) + ","));
        }
        for (String paragraph : c.paragraphs()) body.append(paragraph(esc(paragraph)));
        if (c.detail() != null && !c.detail().isBlank()) {
            body.append("<div style=\"margin:18px 0;padding:14px 16px;border-radius:10px;background:#f1f5f9;"
                    + "border-left:4px solid " + BRAND + ";color:#0f172a;"
                    + "font-size:15px;line-height:1.55\">")
                    .append(esc(c.detail()).replace("\r\n", "\n").replace("\n", "<br>")).append("</div>");
        }
        String href = esc(url);
        body.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:26px 0 8px\"><tr>"
                + "<td style=\"border-radius:10px;background:" + BRAND + ";background-image:linear-gradient(135deg," + BRAND + "," + ACCENT + ")\">"
                + "<a href=\"" + href + "\" style=\"display:inline-block;padding:14px 28px;font-size:16px;font-weight:700;"
                + "color:#ffffff;text-decoration:none;border-radius:10px\">" + esc(c.button()) + "</a></td></tr></table>");
        body.append("<p style=\"margin:18px 0 0;font-size:13px;line-height:1.5;color:#64748b\">Button not working? Copy this link into your browser:<br>"
                + "<a href=\"" + href + "\" style=\"color:" + BRAND + ";word-break:break-all\">" + href + "</a></p>");

        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light\"><title>" + esc(c.subject()) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#eef2ff;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:#eef2ff\">" + esc(c.preheader()) + "</div>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#eef2ff\"><tr><td align=\"center\" style=\"padding:28px 14px\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px\">"
                // brand row
                + "<tr><td style=\"padding:0 4px 14px;font-size:20px;font-weight:800;color:#0f172a\">"
                + "<span style=\"display:inline-block;width:30px;height:30px;line-height:30px;text-align:center;border-radius:8px;color:#fff;"
                + "background:" + BRAND + ";background-image:linear-gradient(135deg," + BRAND + "," + ACCENT + ");margin-right:8px\">J</span>"
                + "Jach<span style=\"color:" + BRAND + "\">AI</span></td></tr>"
                // card
                + "<tr><td style=\"background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 6px 24px rgba(79,70,229,.12)\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td align=\"center\" style=\"padding:30px 24px 24px;background:" + BRAND + ";background-image:linear-gradient(135deg," + BRAND + "," + ACCENT + ")\">"
                + "<div style=\"width:60px;height:60px;line-height:60px;margin:0 auto 12px;border-radius:50%;background:rgba(255,255,255,.22);font-size:30px\">" + c.icon() + "</div>"
                + "<div style=\"font-size:24px;line-height:1.25;font-weight:800;color:#ffffff\">" + esc(c.heading()) + "</div></td></tr>"
                + "<tr><td style=\"padding:28px 28px 30px;color:#1e293b;font-size:16px;line-height:1.6\">" + body
                + "<hr style=\"border:0;border-top:1px solid #e2e8f0;margin:26px 0 14px\">"
                + "<p style=\"margin:0;font-size:13px;line-height:1.5;color:#64748b\">" + esc(c.note()) + "</p>"
                + "</td></tr></table></td></tr>"
                // footer
                + "<tr><td align=\"center\" style=\"padding:18px 10px 0;font-size:12px;line-height:1.6;color:#64748b\">"
                + "Sent by JachAI · Assess with confidence<br>You get this email because of activity on your JachAI account."
                + "</td></tr></table></td></tr></table></body></html>";
    }

    private static String text(Content c, String name, String url) {
        StringBuilder out = new StringBuilder();
        if (!name.isBlank() && !name.equals("there")) out.append("Hello ").append(name).append(",\n\n");
        for (String paragraph : c.paragraphs()) out.append(paragraph).append("\n\n");
        if (c.detail() != null && !c.detail().isBlank()) out.append(c.detail()).append("\n\n");
        out.append(c.button()).append(":\n").append(url).append("\n\n").append(c.note()).append("\n\n— JachAI");
        return out.toString();
    }

    private static String paragraph(String escaped) {
        return "<p style=\"margin:0 0 14px\">" + escaped + "</p>";
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    /** Keeps control characters (line breaks inside a name, say) out of subjects and greetings. */
    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }
}
