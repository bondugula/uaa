/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.integration.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudfoundry.identity.uaa.ServerRunning;
import org.cloudfoundry.identity.uaa.client.ClientConstants;
import org.cloudfoundry.identity.uaa.integration.util.IntegrationTestUtils;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.test.UaaTestAccounts;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.client.RestTemplate;

import com.nurego.Nurego;
import com.nurego.model.Entitlement;
import com.nurego.model.Subscription;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DefaultIntegrationTestConfig.class)
public class SpringMeteringFilterIT {

    private static final String USERS_FEATURE_ID = "number_of_users";

    private static final String TOKEN_FEATURE_ID = "number_of_tokens";

    @Autowired
    @Rule
    public IntegrationTestRule integrationTestRule;

    @Autowired
    WebDriver webDriver;

    @Value("${integration.test.base_url}")
    String baseUrl;

    @Value("${ORG_ID}")
    String orgId;

    @Value("${PLAN_ID}")
    String planId;

    @Value("${NUREGO_API_URL}")
    String nuregoApiUrl;

    @Value("${NUREGO_API_KEY}")
    String nuregoApiKey;

    private Subscription subscription;

    ServerRunning serverRunning = ServerRunning.isRunning();

    private final String zoneId = "test-zone-uaa";

    private RestTemplate adminClient;
    private RestTemplate identityClient;
    private RestTemplate zoneAdminClient;

    private String adminUserEmail;
    private final String zoneUrl = "http://" + this.zoneId + ".localhost:8080/uaa";

    @Before
    public void createSubscription() throws Exception {
        Nurego.apiKey = this.nuregoApiKey;
        Nurego.setApiBase(this.nuregoApiUrl);
        Map<String, Object> params = new HashMap<>();
        params.put("plan_id", this.planId);
        params.put("external_subscription_id", this.zoneId);
        params.put("provider", "cloud-foundry");

        try {
            this.subscription = Subscription.create(this.orgId, params);
            System.out.println("******** Subscription created: " + this.subscription.toString());

            System.out.println("******** Entitlements: " + this.subscription.entitlements().getData().toString());
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("Failed to create Nurego subscription.");
        }
    }

    @Before
    public void setupZone() throws Exception {
        // admin client rest template - to create users on base uaa
        this.adminClient = IntegrationTestUtils.getClientCredentialsTemplate(
                IntegrationTestUtils.getClientCredentialsResource(this.baseUrl, new String[0], "admin", "adminsecret"));
        // identity client rest template
        this.identityClient = IntegrationTestUtils
                .getClientCredentialsTemplate(IntegrationTestUtils.getClientCredentialsResource(this.baseUrl,
                        new String[] { "zones.write", "zones.read", "scim.zones" }, "identity", "identitysecret"));
        // create the zone
        IntegrationTestUtils.createZoneOrUpdateSubdomain(this.identityClient, this.baseUrl, this.zoneId, this.zoneId);

        // this.adminUserEmail = new RandomValueStringGenerator().generate() +"@samltesting.org";
        this.adminUserEmail = "adminUserTest@filter.org";
        ScimUser adminUser = IntegrationTestUtils.createUser(this.adminClient, this.baseUrl, this.adminUserEmail,
                "firstname", "lastname", this.adminUserEmail, true);
        IntegrationTestUtils.makeZoneAdmin(this.identityClient, this.baseUrl, adminUser.getId(), this.zoneId);

        System.out.println("****** CREATED ZONE ADMIN ******");

        String zoneAdminToken = IntegrationTestUtils.getAuthorizationCodeToken(this.serverRunning,
                UaaTestAccounts.standard(this.serverRunning), "identity", "identitysecret", this.adminUserEmail,
                "secr3T");

        System.out.println("****** GOT ZONE ADMIN TOKEN ******");
        System.out.println("Zone Admin Token: " + zoneAdminToken);

        String adminClientInZone = "zone-admin-client";
        BaseClientDetails clientDetails = new BaseClientDetails(adminClientInZone, null, "openid,user_attributes",
                "authorization_code,client_credentials", "uaa.admin,scim.read,scim.write,uaa.resource", this.zoneUrl);
        clientDetails.setClientSecret("zone-admin-client-secret");
        clientDetails.addAdditionalInformation(ClientConstants.AUTO_APPROVE, true);
        IntegrationTestUtils.createClientAsZoneAdmin(zoneAdminToken, this.baseUrl, this.zoneId, clientDetails);

        System.out.println("****** CREATED ZONE ADMIN CLIENT ******");

        this.zoneAdminClient = IntegrationTestUtils
                .getClientCredentialsTemplate(IntegrationTestUtils.getClientCredentialsResource(this.zoneUrl,
                        new String[0], "zone-admin-client", "zone-admin-client-secret"));
        System.out.println("****** END SETUP ZONE ******");
    }

    @After
    public void cancelSubscription() throws Exception {
        try {
            Subscription.cancel(this.orgId, this.subscription.getId());
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail("Failed to cancel Nurego subscription.");
        }
    }

    // @Before
    // @After
    public void logout_and_clear_cookies() {
        try {
            this.webDriver.get(this.baseUrl + "/logout.do");
        } catch (org.openqa.selenium.TimeoutException x) {
            // try again - this should not be happening - 20 second timeouts
            this.webDriver.get(this.baseUrl + "/logout.do");
        }
        this.webDriver.manage().deleteAllCookies();
    }

    @Test
    public void testFilter() throws Exception {
        Double beforeUsedAmountUsers = getEntitlementUsageByFeatureId(USERS_FEATURE_ID, this.zoneId);
        Double beforeUsedAmountTokens = getEntitlementUsageByFeatureId(TOKEN_FEATURE_ID, this.subscription.getId());

        String zoneUserEmail = "zoneUser@filter.org";
        // call user api
        IntegrationTestUtils.createUser(this.zoneAdminClient, this.zoneUrl, zoneUserEmail,
                "firstname", "lastname", zoneUserEmail, true);

        /*
        Thread.sleep(1000);
        zoneUserEmail = "zoneUser2@filter.org";
        IntegrationTestUtils.createUser(this.zoneAdminClient, this.zoneUrl, zoneUserEmail,
                "firstname2", "lastname2", zoneUserEmail, true);*/

        // check Nurego amounts
        Double afterUsedAmountUsers = getEntitlementUsageByFeatureId(USERS_FEATURE_ID, this.zoneId);
        Double afterUsedAmountTokens = getEntitlementUsageByFeatureId(TOKEN_FEATURE_ID, this.zoneId);

        Assert.assertEquals(1.0, afterUsedAmountUsers - beforeUsedAmountUsers, 0.0);
        Assert.assertEquals(1.0, afterUsedAmountTokens - beforeUsedAmountTokens, 0.0);
    }

    private Double getEntitlementUsageByFeatureId(final String featureId, final String subscriptionId)
            throws Exception {
        Entitlement entitlement = getEntitlementByFeatureId(featureId, subscriptionId);
        if (entitlement == null) {
            throw new IllegalArgumentException(String.format("Feature '%s' does not exist.", featureId));
        }
        return entitlement.getCurrentUsedAmount();
    }

    private Entitlement getEntitlementByFeatureId(final String featureId, final String subscriptionId)
            throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("provider", "cloud-foundry");
        params.put("external_subscription_id", this.zoneId);
        List<Entitlement> entitlements = Entitlement.retrieve(subscriptionId, params).getData();
        System.out.println("******** Entitlements retrieved: " + entitlements.toString());
        for (Entitlement entitlement : entitlements) {
            if (entitlement.getFeatureId().equals(featureId)) {
                return entitlement;
            }
        }

        return null;
    }
}