package com.blogPlatform.III.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// finds @ConfigurationProperties records such as JwtProperties (app.jwt.*)
@ConfigurationPropertiesScan
// runs the @Scheduled clean-ups (expired entries of the token blacklist and of the session registry)
@EnableScheduling
public class BlogApplication {

	public static void main(String[] args) {
		SpringApplication.run(BlogApplication.class, args);
	}
}
