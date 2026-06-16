package dev.notypie.gateway.configurations

import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RemoteAddressResolverConfig {
    /**
     * Resolves the real client IP behind the istio hop. `maxTrustedIndex(hops)` trusts N entries
     * from the right of X-Forwarded-For, so a client cannot spoof a leftmost-prepended address.
     */
    @Bean
    fun remoteAddressResolver(appConfig: AppConfig): RemoteAddressResolver =
        XForwardedRemoteAddressResolver.maxTrustedIndex(appConfig.security.trustedProxyHops)
}
