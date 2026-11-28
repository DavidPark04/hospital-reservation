package kr.dmu.hospital.auth;

import java.util.Properties;
import kr.dmu.hospital.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class VerificationMailSender {
    private final String username;
    private final JavaMailSenderImpl sender;

    public VerificationMailSender(@Value("${app.verification.smtp-username:}") String username,
            @Value("${app.verification.smtp-password:}") String password) {
        this.username = username.trim();
        sender = new JavaMailSenderImpl();
        sender.setHost("smtp.gmail.com");
        sender.setPort(587);
        sender.setUsername(this.username);
        sender.setPassword(password.replace(" ", ""));
        sender.setDefaultEncoding("UTF-8");
        Properties settings = sender.getJavaMailProperties();
        settings.setProperty("mail.smtp.auth", "true");
        settings.setProperty("mail.smtp.starttls.enable", "true");
        settings.setProperty("mail.smtp.starttls.required", "true");
        settings.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        settings.setProperty("mail.smtp.connectiontimeout", "8000");
        settings.setProperty("mail.smtp.timeout", "8000");
        settings.setProperty("mail.smtp.writetimeout", "8000");
    }

    public boolean enabled() {
        return MemberIdentity.validEmail(username) && sender.getPassword() != null && !sender.getPassword().isBlank();
    }

    public void sendCode(String email, String code) {
        if (!enabled()) throw unavailable();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(username);
        message.setTo(email);
        message.setSubject("메디픽 이메일 인증번호");
        message.setText("메디픽 이메일 인증번호는 " + code + " 입니다.\n\n5분 안에 화면에 입력해 주세요.\n본인이 요청하지 않았다면 이 메일을 무시해 주세요.");
        try { sender.send(message); }
        catch (RuntimeException error) { throw unavailable(); }
    }

    private static ApiException unavailable() {
        return new ApiException(503, "인증 메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}
