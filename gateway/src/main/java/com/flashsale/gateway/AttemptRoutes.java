package com.flashsale.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.adaptCachedBody;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
class AttemptRoutes {

	@Bean
	RouterFunction<ServerResponse> attempts(AttemptEdge edge, @Value("${gateway.sale-url}") String saleUrl) {
		return route("attempts")
				.POST("/attempts", http())
				.filter((request, next) -> {
					String body = MvcUtils.cacheAndReadBody(request, String.class).orElse("");
					Optional<String> answer = edge.answer(edge.userId(body));
					if (answer.isPresent()) {
						return edge.answered(answer.get());
					}
					var forwarded = uri(saleUrl).apply(adaptCachedBody().apply(request));
					return next.handle(forwarded);
				})
				.build();
	}

}
