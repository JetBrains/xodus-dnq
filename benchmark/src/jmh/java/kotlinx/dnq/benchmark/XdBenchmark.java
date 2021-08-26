/**
 * Copyright 2006 - 2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.benchmark;

import jetbrains.exodus.entitystore.Entity;
import kotlinx.dnq.XdModel;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class XdBenchmark {
    private XdPerformanceUtil util = new XdPerformanceUtil();
    private Entity user;

    @Setup
    public void setup() {
        util.initDatabase();
        user = util.createUser();
    }

    @TearDown
    public void tearDown() {
        util.closeDatabase();
    }

    @Benchmark
    @Warmup(iterations = 4, time = 1)
    @Measurement(iterations = 5, time = 3)
    @Fork(4)
    public XdPerformanceUtil.XdUser xdModel_toXd() {
        return XdModel.INSTANCE.toXd(user);
    }
}
