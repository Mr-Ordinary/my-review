package com.review.vt;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.review.vt.mapper")
public class MyReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyReviewApplication.class, args);
    }
}
