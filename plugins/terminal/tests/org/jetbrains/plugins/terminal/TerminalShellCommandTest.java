/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.plugins.terminal;

import com.google.common.collect.Maps;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @author traff
 */
public class TerminalShellCommandTest extends TestCase {
  public void testDontAddAnything() {
    if (SystemInfo.isUnix) {
      doTest(new String[]{"myshell", "someargs", "-i"}, "myshell someargs -i", Maps.newHashMap());
      doTest(new String[]{"myshell", "someargs", "--login"}, "myshell someargs --login", Maps.newHashMap());
    }
  }

  public void testAddInteractiveOrLogin() {
    if (SystemInfo.isLinux) {
      contains("bash someargs", true, Maps.newHashMap(), "-i", "someargs", "bash");
      contains("bash someargs", false, Maps.newHashMap(), "-i", "someargs", "bash");
    }
    else if (SystemInfo.isMac) {
      contains("bash someargs", true, Maps.newHashMap(), "someargs", "bash");
      contains("bash someargs", false, Maps.newHashMap(), "--login", "someargs", "bash");
    }
  }

  public void testAddRcConfig() {
    if (SystemInfo.isUnix) {
      hasRcConfig("bash -i", "jediterm-bash.in", Maps.newHashMap());
      hasRcConfig("bash --login", "jediterm-bash.in", Maps.newHashMap());
      Map<String, String> envs = Maps.newHashMap();
      hasRcConfig("bash --rcfile ~/.bashrc", "jediterm-bash.in", envs);
      assertTrue(envs.get("JEDITERM_USER_RCFILE").contains(".bashrc"));
    }
  }

  private static void hasRcConfig(String path, String configName, Map<String, String> envs) {
    List<String> res = Arrays.asList(
      LocalTerminalDirectRunner.getCommand(path, envs, true));
    assertEquals("--rcfile", res.get(1));
    assertTrue(res.get(2).contains(configName));
  }

  private static void doTest(String[] expected, String path, Map<String, String> envs) {
    assertEquals(Arrays.asList(expected), Arrays.asList(
      LocalTerminalDirectRunner.getCommand(path, envs, true)));
  }

  private static void contains(@NotNull String shellPath, boolean shellIntegration, Map<String, String> envs, String... item) {
    List<String> result = Arrays.asList(LocalTerminalDirectRunner.getCommand(shellPath, envs, shellIntegration));
    for (String i : item) {
      assertTrue(i + " isn't in " + StringUtil.join(result, " "), result.contains(i));
    }
  }
}
