package com.aicoo.common;
import org.springframework.stereotype.Component;
import java.util.UUID;
import static com.aicoo.common.Db.p;

@Component public class Audit {
    private final Db db; public Audit(Db db){this.db=db;}
    public void record(UUID workspace,UUID user,String action,String type,UUID object){db.update("INSERT INTO audit_logs(id,workspace_id,actor_user_id,action,object_type,object_id) VALUES(:id,:ws,:user,:action,:type,:object)",p("id",UUID.randomUUID(),"ws",workspace,"user",user,"action",action,"type",type,"object",object));}
}
