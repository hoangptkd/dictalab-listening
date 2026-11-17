package com.dictalab.vocabulary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;

/** Operator CLI: reuses catalog business rules inside one local DB transaction, with no server or AI startup. */
class ImportVocabularyLocal {
    public static void main(String[] args) throws Exception {
        var env=System.getenv();String url=env.get("VOCABULARY_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/dictalab_personalized_local_20261102"))
            throw new IllegalArgumentException("Only the established local QA database is allowed");
        var data=new DriverManagerDataSource(url,env.get("VOCABULARY_DB_USER"),env.get("VOCABULARY_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(data);
        String email=jdbc.queryForObject("SELECT email FROM app_users WHERE id=14 AND display_name='Scenario Local QA'",String.class);
        if(!email.equals(env.get("VOCABULARY_QA_EMAIL"))) throw new IllegalStateException("Local QA identity mismatch");
        var json=new ObjectMapper();var repo=new VocabularyRepository(jdbc,json);
        try(var validation=Validation.buildDefaultValidatorFactory()) {
            var service=new VocabularyCatalogService(repo,new VocabularyContentService(repo),validation.getValidator());
            var bundle=json.readValue(Files.readAllBytes(Path.of(args[0])),VocabularyCatalogService.Bundle.class);
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(data));transaction.setTimeout(60);
            var result=transaction.execute(status->{
                var preview=service.preview(bundle);
                if(args.length<2 || !"--apply".equals(args[1])) return preview;
                return service.apply(14,UUID.randomUUID().toString(),new VocabularyCatalogService.Apply(bundle,(String)preview.get("previewHash")));
            });
            System.out.println(json.writeValueAsString(result));
        }
    }
}
