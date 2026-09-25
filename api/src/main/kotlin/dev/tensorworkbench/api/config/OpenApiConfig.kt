package dev.tensorworkbench.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Tensor Workbench API")
            .version("0.1.0")
            .description(
                "Synthetic 3D array workloads: datasets, runs, sweeps, bounded previews and downloads. " +
                    "The computation is a documented toy function, not a physics solver.",
            ),
    )

    @Bean
    fun publicApi(): GroupedOpenApi = GroupedOpenApi.builder().group("public").pathsToMatch("/api/**").build()

    @Bean
    fun internalApi(): GroupedOpenApi = GroupedOpenApi.builder().group("internal").pathsToMatch("/internal/**").build()
}
