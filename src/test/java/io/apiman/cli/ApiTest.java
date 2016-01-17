/*
 * Copyright 2016 Pete Cornish
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apiman.cli;

import io.apiman.cli.common.BaseTest;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ApiTest extends BaseTest {
    private static final String ORG_NAME = "apitest";

    @Test
    public void test1_create() {
        //set up
        createOrg(ORG_NAME);

        // test
        Cli.main("api", "create",
                "--debug",
                "--server", getApimanUrl(),
                "--serverUsername", "admin",
                "--serverPassword", "admin123!",
                "--name", "example",
                "--endpoint", "http://example.com",
                "--initialVersion", "1.0",
                "--public",
                "--orgName", ORG_NAME);
    }

    @Test
    public void test2_addPolicy() {
        Cli.main("api", "policy", "add",
                "--debug",
                "--server", getApimanUrl(),
                "--serverUsername", "admin",
                "--serverPassword", "admin123!",
                "--name", "example",
                "--version", "1.0",
                "--policyName", "test-policy",
                "--configFile", "examples/policies/empty-policy-config.json",
                "--orgName", ORG_NAME);
    }

    @Test
    public void test3_publish() {
        Cli.main("api", "publish",
                "--debug",
                "--server", getApimanUrl(),
                "--serverUsername", "admin",
                "--serverPassword", "admin123!",
                "--name", "example",
                "--version", "1.0",
                "--orgName", ORG_NAME);
    }

    @Test
    public void test4_list() {
        Cli.main("api", "list",
                "--debug",
                "--server", getApimanUrl(),
                "--serverUsername", "admin",
                "--serverPassword", "admin123!",
                "--orgName", ORG_NAME);
    }
}
