/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.driver.internal.mapper.processor;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Handles warning and error messages for a processed method.
 *
 * <p>We don't issue those messages on the {@link ExecutableElement} directly, because that can lead
 * to imprecise compiler messages if the method is inherited from an already compiled parent type.
 * Consider the following situation:
 *
 * <pre>
 *   interface BaseDao {
 *     &#64;Select
 *     void select();
 *   }
 *   &#64;Dao
 *   interface ConcreteDao extends BaseDao {}
 * </pre>
 *
 * If {@code BaseDao} belongs to a JAR dependency, it is already compiled and the warning or error
 * message can't reference a file or line number, it doesn't even mention {@code ConcreteDao}.
 *
 * <p>The goal of this class is to detect those cases, and issue the message on {@code ConcreteDao}
 * instead.
 */
public class MethodMessager {

  private final DecoratedMessager delegate;

  // The element where we'll issue the message
  private final Element targetElement;
  // Additional location information that will get prepended to every message
  private final String locationInfo;

  /**
   * @param processedType the type that we are currently processing ({@code ConcreteDao} in the
   *     example above).
   * @param declaringType the type that declares the method ({@code BaseDao} in the example above).
   *     That's the one that might be already compiled.
   */
  public MethodMessager(
      @NonNull ExecutableElement method,
      @NonNull TypeElement processedType,
      @NonNull TypeElement declaringType,
      @NonNull ProcessorContext context) {

    this.delegate = context.getMessager();

    if (processedType.equals(declaringType) || isSourceFile(declaringType)) {
      this.targetElement = method;
      this.locationInfo = "";
    } else {
      this.targetElement = processedType;
      this.locationInfo =
          String.format("[%s inherited from %s] ", method, declaringType.getSimpleName());
    }
  }

  public void warn(String template, Object... arguments) {
    delegate.warn(targetElement, locationInfo + template, arguments);
  }

  public void error(String template, Object... arguments) {
    delegate.error(targetElement, locationInfo + template, arguments);
  }

  // TODO factor with similar method in DaoMethodGenerator?
  private static boolean isSourceFile(TypeElement element) {
    try {
      Class.forName(element.getQualifiedName().toString());
      return false;
    } catch (ClassNotFoundException e) {
      return true;
    }
  }
}
