package edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc;

import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import edu.harvard.iq.dataverse.UserServiceBean;
import edu.harvard.iq.dataverse.api.auth.BearerTokenAuthMechanism;
import edu.harvard.iq.dataverse.api.auth.doubles.BearerTokenKeyContainerRequestTestFake;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.UserRecordIdentifier;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.mocks.MockAuthenticatedUser;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.Set;

import static edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc.OIDCAuthenticationProviderFactoryIT.clientId;
import static edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc.OIDCAuthenticationProviderFactoryIT.clientSecret;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

@Tag("testcontainers")
@Testcontainers
@ExtendWith(MockitoExtension.class)
// NOTE: order is important here - Testcontainers must be first, otherwise it's not ready when we call getAuthUrl()
@LocalJvmSettings
@JvmSetting(key = JvmSettings.OIDC_CLIENT_ID, value = clientId)
@JvmSetting(key = JvmSettings.OIDC_CLIENT_SECRET, value = clientSecret)
@JvmSetting(key = JvmSettings.OIDC_AUTH_SERVER_URL, method = "getAuthUrl")
class OIDCAuthenticationProviderFactoryIT {
    
    // NOTE: the following values are taken from the realm import file!
    static final String clientId = "oidc-client";
    static final String clientSecret = "ss6gE8mODCDfqesQaSG3gwUwZqZt547E";
    static final String realm = "oidc-realm";
    static final String adminUser = "kcuser";
    static final String adminPassword = "kcpassword";
    static final String clientIdAdminCli = "admin-cli";
    
    // The realm JSON resides in conf/keycloak/oidc-realm.json and gets avail here using <testResources> in pom.xml
    @Container
    static KeycloakContainer keycloakContainer = new KeycloakContainer("quay.io/keycloak/keycloak:19.0")
        .withRealmImportFile("keycloak/oidc-realm.json")
        .withAdminUsername(adminUser)
        .withAdminPassword(adminPassword);
    
    // simple method to retrieve the issuer URL, referenced to by @JvmSetting annotations (do no delete)
    private static String getAuthUrl() {
        return keycloakContainer.getAuthServerUrl() + "realms/" + realm;
    }
    
    OIDCAuthProvider getProvider() throws Exception {
        OIDCAuthProvider oidcAuthProvider = (OIDCAuthProvider) OIDCAuthenticationProviderFactory.buildFromSettings();
        
        assumeTrue(oidcAuthProvider.getMetadata().getTokenEndpointURI().toString()
            .startsWith(keycloakContainer.getAuthServerUrl()));
        
        return oidcAuthProvider;
    }
    
    Keycloak getAdminClient() {
        return KeycloakBuilder.builder()
            .serverUrl(keycloakContainer.getAuthServerUrl())
            .realm(realm)
            .clientId(clientIdAdminCli)
            .username(keycloakContainer.getAdminUsername())
            .password(keycloakContainer.getAdminPassword())
            .build();
    }
    
    String getBearerToken() throws Exception {
        Keycloak keycloak = getAdminClient();
        return keycloak.tokenManager().getAccessTokenString();
    }
    
    @Test
    void testCreateProvider() throws Exception {
        OIDCAuthProvider oidcAuthProvider = getProvider();
        String token = getBearerToken();
        assumeFalse(token == null);
        
        Optional<UserInfo> info = oidcAuthProvider.getUserInfo(new BearerAccessToken(token));
        
        assertTrue(info.isPresent());
        assertEquals(adminUser, info.get().getPreferredUsername());
    }
    
    @Mock
    UserServiceBean userService;
    @Mock
    AuthenticationServiceBean authService;
    
    @InjectMocks
    BearerTokenAuthMechanism bearerTokenAuthMechanism;
    
    @Test
    @JvmSetting(key = JvmSettings.FEATURE_FLAG, varArgs = "api-bearer-auth", value = "true")
    void testApiBearerAuth() throws Exception {
        assumeFalse(userService == null);
        assumeFalse(authService == null);
        assumeFalse(bearerTokenAuthMechanism == null);
        
        // given
        // Get the access token from the remote Keycloak in the container
        String accessToken = getBearerToken();
        assumeFalse(accessToken == null);
        
        OIDCAuthProvider oidcAuthProvider = getProvider();
        // This will also receive the details from the remote Keycloak in the container
        UserRecordIdentifier identifier = oidcAuthProvider.getUserIdentifier(new BearerAccessToken(accessToken)).get();
        String token = "Bearer " + accessToken;
        BearerTokenKeyContainerRequestTestFake request = new BearerTokenKeyContainerRequestTestFake(token);
        AuthenticatedUser user = new MockAuthenticatedUser();
        
        // setup mocks (we don't want or need a database here)
        when(authService.getAuthenticationProviderIdsOfType(OIDCAuthProvider.class)).thenReturn(Set.of(oidcAuthProvider.getId()));
        when(authService.getAuthenticationProvider(oidcAuthProvider.getId())).thenReturn(oidcAuthProvider);
        when(authService.lookupUser(identifier)).thenReturn(user);
        when(userService.updateLastApiUseTime(user)).thenReturn(user);
        
        // when (let's do this again, but now with the actual subject under test!)
        User lookedUpUser = bearerTokenAuthMechanism.findUserFromRequest(request);
        
        // then
        assertNotNull(lookedUpUser);
        assertEquals(user, lookedUpUser);
    }
}