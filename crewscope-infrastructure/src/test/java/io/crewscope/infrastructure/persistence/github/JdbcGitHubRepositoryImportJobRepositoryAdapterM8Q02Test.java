package io.crewscope.infrastructure.persistence.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.github.GitHubRepositoryImportJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Verifies that Spring can apply its Repository exception-translation proxy in release profiles. */
class JdbcGitHubRepositoryImportJobRepositoryAdapterM8Q02Test {

    @Test
    void remainsProxyableWithClassBasedSpringAop() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NamedParameterJdbcTemplate.class,
                    () -> mock(NamedParameterJdbcTemplate.class));
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class,
                    () -> {
                        var postProcessor = new PersistenceExceptionTranslationPostProcessor();
                        postProcessor.setProxyTargetClass(true);
                        return postProcessor;
                    });
            context.registerBean(JdbcGitHubRepositoryImportJobRepositoryAdapter.class);
            context.refresh();

            assertThat(context.getBean(GitHubRepositoryImportJobRepository.class)).isNotNull();
        }
    }
}
