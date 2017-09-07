/**
 * Copyright 2006 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore;

import jetbrains.exodus.core.execution.Job;
import jetbrains.exodus.core.execution.JobProcessor;
import jetbrains.exodus.core.execution.JobProcessorExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExceptionHandlerImpl implements JobProcessorExceptionHandler {
  private static Logger logger = LoggerFactory.getLogger(ExceptionHandlerImpl.class);

  ExceptionHandlerImpl() {
  }

  public void handle(JobProcessor p, Job j, Throwable e) {
    if (logger.isErrorEnabled()) {
      logger.error("Exception inside job processor [" + p + "] while executing job [" + j + "]", e);
    }
  }
}
