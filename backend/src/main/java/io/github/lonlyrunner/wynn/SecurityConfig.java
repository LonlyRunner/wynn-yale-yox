package io.github.lonlyrunner.wynn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean InMemoryUserDetailsManager users(@Value("${app.admin.username}") String username,@Value("${app.admin.password}") String password,PasswordEncoder encoder){return new InMemoryUserDetailsManager(User.withUsername(username).password(encoder.encode(password)).roles("OWNER").build());}
    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)throws Exception{return configuration.getAuthenticationManager();}
    @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{return http.csrf(csrf->csrf.disable()).authorizeHttpRequests(auth->auth.requestMatchers("/api/public/**","/api/auth/**","/api/ai/**","/actuator/health").permitAll().requestMatchers("/api/admin/**").hasRole("OWNER").anyRequest().permitAll()).build();}
}
