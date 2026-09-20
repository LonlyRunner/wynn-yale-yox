package io.github.lonlyrunner.wynn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

record LoginRequest(@NotBlank String username,@NotBlank String password){}
record AiJobRequest(@NotBlank String provider,@NotBlank String type,@NotBlank String prompt){}

@RestController @RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationManager manager;
    AuthController(AuthenticationManager manager){this.manager=manager;}
    @PostMapping("/login") Map<String,Object> login(@Valid @RequestBody LoginRequest body,HttpServletRequest request){Authentication auth=manager.authenticate(new UsernamePasswordAuthenticationToken(body.username(),body.password()));var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);return Map.of("authenticated",true,"username",auth.getName());}
    @ExceptionHandler(AuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED) Map<String,String> invalidCredentials(){return Map.of("error","INVALID_CREDENTIALS");}
    @PostMapping("/logout") void logout(HttpServletRequest request){var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();}
    @GetMapping("/me") Map<String,Object> me(Authentication auth){return Map.of("authenticated",auth!=null&&auth.isAuthenticated(),"username",auth==null?"":auth.getName());}
}

@RestController @RequestMapping("/api/public")
class PublicController {
    private final PostRepository posts;
    PublicController(PostRepository posts){this.posts=posts;}
    @GetMapping("/posts") Object posts(){return posts.findByPublishedTrueOrderByCreatedAtDesc();}
    @GetMapping("/profile") Object profile(){return Map.of("displayName","Wynn Yale Yox","taglineZh","随性而行，无拘无定。","taglineEn","Move freely, remain undefined.");}
}

@RestController @RequestMapping("/api/admin")
class AdminController {
    private final AiJobRepository jobs;
    private final OssService oss;
    AdminController(AiJobRepository jobs,OssService oss){this.jobs=jobs;this.oss=oss;}
    @PostMapping("/ai/jobs") Object createJob(@Valid @RequestBody AiJobRequest request){AiJob job=jobs.save(new AiJob(request.provider(),request.type(),request.prompt(),"WAITING_FOR_PROVIDER_CONFIG"));return Map.of("id",job.id,"status",job.status);}
    @GetMapping("/ai/jobs") Object jobs(){return jobs.findAll();}
    @PostMapping("/oss/upload-url") Object uploadUrl(@RequestBody Map<String,String> body){return Map.of("url",oss.presignedPutUrl(body.getOrDefault("objectKey","uploads/file.bin")));}
}

@Configuration
class SeedData {
    @Bean CommandLineRunner seed(PostRepository posts){return args->{if(posts.count()==0){posts.save(new Post("reliable-agent","从一次对话到一个可靠的 Agent","From a Conversation to a Reliable Agent","AI ENGINEERING"));posts.save(new Post("model-routing","多模型路由的简单实现","A Simple Multi-model Router","SPRING AI"));}};}
}
