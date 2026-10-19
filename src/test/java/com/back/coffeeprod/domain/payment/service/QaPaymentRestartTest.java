package com.back.coffeeprod.domain.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class QaPaymentRestartTest {
    @ParameterizedTest @ValueSource(strings={"confirm","refund"})
    @DisplayName("AT1-A07 실제 JVM 강제 종료 후 새 JVM은 PG 조회로 승인과 환불을 한 번만 반영한다")
    void actualJvmRestart(String target) throws Exception {
        Path dir=Path.of("build/qa-task6/restart",System.getenv("QA_TASK6_RESTART_PG_URL")==null?"h2":"postgres",target).toAbsolutePath();
        Files.createDirectories(dir);Path marker=dir.resolve("pause.marker"),state=dir.resolve("fixture.json");Files.deleteIfExists(marker);
        try(var pg=new QaPgServer()) {
            Process crash=start(dir,pg,"crash-"+target,target,marker,state);
            try {
                long deadline=System.nanoTime()+Duration.ofSeconds(75).toNanos();
                while(!Files.exists(marker)&&crash.isAlive()&&System.nanoTime()<deadline)Thread.sleep(100);
                assertTrue(Files.exists(marker),"child did not reach PG success boundary; see "+dir);
                assertTrue(crash.isAlive());crash.destroyForcibly();assertTrue(crash.waitFor(10,TimeUnit.SECONDS));
                Process recovered=start(dir,pg,"recover",target,marker,state);
                try{assertTrue(recovered.waitFor(75,TimeUnit.SECONDS),"recovery timeout");assertEquals(0,recovered.exitValue(),"recovery failed; see "+dir);}
                finally{if(recovered.isAlive()){recovered.destroyForcibly();recovered.waitFor(10,TimeUnit.SECONDS);}}
                assertEquals(1,pg.posts("/confirm"));assertEquals(target.equals("refund")?1:0,pg.posts("/cancel"));
                assertTrue(pg.calls.stream().anyMatch(c->c.method().equals("GET")));assertTrue(Files.exists(dir.resolve("recovery-result.json")));
                Files.writeString(dir.resolve("processes.json"),"{\"terminatedPid\":"+crash.pid()+",\"recoveryPid\":"+recovered.pid()+",\"result\":\"PASS\",\"additionalPost\":0}");
            } finally{if(crash.isAlive()){crash.destroyForcibly();crash.waitFor(10,TimeUnit.SECONDS);}}
        }
    }
    Process start(Path dir,QaPgServer pg,String mode,String target,Path marker,Path state) throws Exception {
        Set<String> cp=new LinkedHashSet<>(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent()) {
            if(loader instanceof URLClassLoader urls)for(var url:urls.getURLs())if(url.getProtocol().equals("file"))cp.add(Path.of(url.toURI()).toString());
        }
        Path arguments=dir.resolve(mode+".args");
        Files.writeString(arguments,"-Dfile.encoding=UTF-8\n-cp\n\""+String.join(java.io.File.pathSeparator,cp).replace("\\","/")+"\"\n"+QaRestartChild.class.getName()+"\n");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+arguments);
        var env=builder.environment();env.put("QA_TASK6_RESTART_MODE",mode);env.put("QA_TASK6_TARGET",target);
        env.put("QA_TASK6_MARKER",marker.toString());env.put("QA_TASK6_STATE",state.toString());env.put("QA_TASK6_STUB_URL",pg.url());
        String pgUrl=System.getenv("QA_TASK6_RESTART_PG_URL");
        env.put("QA_TASK6_RESTART_JDBC",pgUrl==null?"jdbc:h2:file:"+dir.resolve("isolated").toString().replace('\\','/')+";WRITE_DELAY=0;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE":pgUrl);
        return builder.redirectErrorStream(true).redirectOutput(dir.resolve(mode+".log").toFile()).start();
    }
}
