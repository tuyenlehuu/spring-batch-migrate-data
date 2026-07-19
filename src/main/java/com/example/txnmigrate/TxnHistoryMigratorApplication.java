package com.example.txnmigrate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TxnHistoryMigratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TxnHistoryMigratorApplication.class, args);
    }
}
