/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.type;

import com.hazelcast.config.Config;
import com.hazelcast.jet.sql.SqlTestSupport;
import com.hazelcast.test.HazelcastSerialClassRunner;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.hazelcast.spi.properties.ClusterProperty.SQL_CUSTOM_TYPES_ENABLED;

@RunWith(HazelcastSerialClassRunner.class)
public class CompactNestedFieldsTest extends SqlTestSupport {

    @BeforeClass
    public static void beforeClass() {
        final Config config = new Config();
        config.getJetConfig().setEnabled(true);
        config.setProperty(SQL_CUSTOM_TYPES_ENABLED.getName(), "true");

        initializeWithClient(3, config, null);
    }

    @Test
    public void test_basicQuerying() {
        client().getSql().execute("CREATE TYPE Office ("
                + "id BIGINT, "
                + "name VARCHAR "
                + ") OPTIONS ('format'='compact', 'compactTypeName'='OfficeCompactType')");

        client().getSql().execute("CREATE TYPE Organization ("
                + "id BIGINT, "
                + "name VARCHAR, "
                + "office Office"
                + ") OPTIONS ('format'='compact', 'compactTypeName'='OrganizationCompactType')");

        client().getSql().execute(
                "CREATE MAPPING test ("
                        + "__key BIGINT,"
                        + "id BIGINT, "
                        + "name VARCHAR, "
                        + "organization Organization"
                        + ")"
                        + "TYPE IMap "
                        + "OPTIONS ("
                        + "'keyFormat'='bigint',"
                        + "'valueFormat'='compact',"
                        + "'valueCompactTypeName'='UserCompactType'"
                        + ")");

        client().getSql().execute("INSERT INTO test VALUES (1, 1, 'user1', (1, 'organization1', (1, 'office1')))");
        assertRowsAnyOrder("SELECT (organization).office.name FROM test", rows(1, "office1"));
    }
}
