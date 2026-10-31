package com.aicoo.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.LoggerFactory;
import java.util.Map;

@Service public class MailDelivery {
    private final JavaMailSender sender;private final String mode,from;
    public MailDelivery(JavaMailSender sender,@Value("${app.mail-mode}")String mode,@Value("${app.mail-from}")String from){this.sender=sender;this.mode=mode;this.from=from;}
    public void deliver(String email,String purpose,String token){if(!mode.equals("smtp"))return;var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject("아이쿠 · "+purpose);message.setText("아이쿠 "+purpose+" 코드입니다. 앱에 다음 코드를 입력해 주세요.\n\n"+token+"\n\n요청하지 않았다면 이 메일을 무시해 주세요.");
        Runnable send=()->{try{sender.send(message);}catch(Exception e){LoggerFactory.getLogger(MailDelivery.class).error("mail_delivery_failed type={}",purpose);}};
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){send.run();}});else send.run();
    }
    public Map<String,Object> result(String key,String token){return mode.equals("local")?Map.of("delivery","local_only",key,token):Map.of("delivery","smtp_requested");}
}
