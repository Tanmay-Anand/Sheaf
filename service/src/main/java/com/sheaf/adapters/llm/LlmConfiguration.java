package com.sheaf.adapters.llm;

import com.sheaf.application.planning.LanguageModels;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The model providers, from sheaf.llm.* (application.yml, overridable by environment). Service-held
 * keys come from SHEAF_*_API_KEY, which may live in the repository's .env (git-ignored); a key the
 * pane sends is used for that request only.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfiguration {

    @Bean
    LanguageModels languageModels(LlmProperties props) {
        return new ProviderRegistry(props);
    }
}
