/*
 * Copyright (c) 2015-present, Parse, LLC.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.parse;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Matchers;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import bolts.Task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ParseFileTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setup() {
    ParseTestUtils.setTestParseUser();
  }

  @After
  public void tearDown() {
    ParseCorePlugins.getInstance().reset();
  }

  @Test
  public void testConstructor() throws Exception {
    String name = "name";
    byte[] data = "hello".getBytes();
    String contentType = "content_type";

    ParseFile file = new ParseFile(name, data, contentType);
    assertEquals("name", file.getName());
    assertEquals("hello", new String(file.getData()));
    assertEquals("content_type", file.getState().mimeType());
    assertTrue(file.isDirty());

    file = new ParseFile(data);
    assertEquals("file", file.getName()); // Default
    assertEquals("hello", new String(file.getData()));
    assertEquals(null, file.getState().mimeType());
    assertTrue(file.isDirty());

    file = new ParseFile(name, data);
    assertEquals("name", file.getName());
    assertEquals("hello", new String(file.getData()));
    assertEquals(null, file.getState().mimeType());
    assertTrue(file.isDirty());

    file = new ParseFile(data, contentType);
    assertEquals("file", file.getName()); // Default
    assertEquals("hello", new String(file.getData()));
    assertEquals("content_type", file.getState().mimeType());
    assertTrue(file.isDirty());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSavingTooLargeFileThrowsException() throws Exception {
    byte[] data = new byte[10 * 1048576 + 1];
    new ParseFile(data);
  }

  @Test
  public void testGetters() {
    ParseFile file = new ParseFile(new ParseFile.State.Builder().url("http://example.com").build());
    assertEquals("http://example.com", file.getUrl());
    assertFalse(file.isDirty());

    // Note: rest of the getters are tested in `testConstructor`
  }

  @Test
  public void testIsDataAvailableCachedInMemory() {
    ParseFile file = new ParseFile(new ParseFile.State.Builder().build());
    file.data = "hello".getBytes();
    assertTrue(file.isDataAvailable());
  }

  @Test
  public void testIsDataAvailableCachedInController() {
    ParseFileController controller = mock(ParseFileController.class);
    when(controller.isDataAvailable(any(ParseFile.State.class))).thenReturn(true);

    ParseCorePlugins.getInstance().registerFileController(controller);

    ParseFile.State state = new ParseFile.State.Builder().build();
    ParseFile file = new ParseFile(state);

    assertTrue(file.isDataAvailable());
    verify(controller).isDataAvailable(state);
  }

  //region testSaveAsync

  @Test
  public void testSaveAsyncNotDirty() throws Exception {
    ParseFileController controller = mock(ParseFileController.class);
    when(controller.isDataAvailable(any(ParseFile.State.class))).thenReturn(true);

    ParseCorePlugins.getInstance().registerFileController(controller);

    ParseFile.State state = new ParseFile.State.Builder().url("http://example.com").build();
    ParseFile file = new ParseFile(state);

    Task<Void> task = file.saveAsync(null, null, null);
    ParseTaskUtils.wait(task);

    verify(controller, never()).saveAsync(
        any(ParseFile.State.class),
        any(byte[].class),
        any(String.class),
        any(ProgressCallback.class),
        Matchers.<Task<Void>>any());
  }

  @Test
  public void testSaveAsyncCancelled() throws Exception {
    ParseFileController controller = mock(ParseFileController.class);
    when(controller.isDataAvailable(any(ParseFile.State.class))).thenReturn(true);

    ParseCorePlugins.getInstance().registerFileController(controller);

    ParseFile.State state = new ParseFile.State.Builder().build();
    ParseFile file = new ParseFile(state);

    Task<Void> task = file.saveAsync(null, null, Task.<Void>cancelled());
    task.waitForCompletion();
    assertTrue(task.isCancelled());

    verify(controller, never()).saveAsync(
        any(ParseFile.State.class),
        any(byte[].class),
        any(String.class),
        any(ProgressCallback.class),
        Matchers.<Task<Void>>any());
  }

  // TODO(grantland): testSaveAsyncNotDirtyAfterQueueAwait
  // TODO(grantland): testSaveAsyncSuccess
  // TODO(grantland): testSaveAsyncFailure

  //endregion

  // TODO(grantland): testGetDataAsync (same as saveAsync)

  @Test
  public void testTaskQueuedMethods() throws Exception {
    ParseFile.State state = new ParseFile.State.Builder().build();
    File cachedFile = temporaryFolder.newFile("temp");

    ParseFileController controller = mock(ParseFileController.class);
    when(controller.saveAsync(
        any(ParseFile.State.class),
        any(byte[].class),
        any(String.class),
        any(ProgressCallback.class),
        Matchers.<Task<Void>>any())).thenReturn(Task.forResult(state));
    when(controller.fetchAsync(
        any(ParseFile.State.class),
        any(String.class),
        any(ProgressCallback.class),
        Matchers.<Task<Void>>any())).thenReturn(Task.forResult(cachedFile));

    ParseCorePlugins.getInstance().registerFileController(controller);

    ParseFile file = new ParseFile(state);

    TaskQueueTestHelper queueHelper = new TaskQueueTestHelper(file.taskQueue);
    queueHelper.enqueue();

    Task<Void> saveTaskA = file.saveAsync(null, null, null);
    queueHelper.enqueue();
    Task<byte[]> getDataTaskA = file.getDataInBackground();
    queueHelper.enqueue();
    Task<Void> saveTaskB = file.saveAsync(null, null, null);
    queueHelper.enqueue();
    Task<byte[]> getDataTaskB = file.getDataInBackground();

    Thread.sleep(50);
    assertFalse(saveTaskA.isCompleted());
    queueHelper.dequeue();
    ParseTaskUtils.wait(saveTaskA);

    Thread.sleep(50);
    assertFalse(getDataTaskA.isCompleted());
    queueHelper.dequeue();
    ParseTaskUtils.wait(getDataTaskA);

    Thread.sleep(50);
    assertFalse(saveTaskB.isCompleted());
    queueHelper.dequeue();
    ParseTaskUtils.wait(saveTaskB);

    Thread.sleep(50);
    assertFalse(getDataTaskB.isCompleted());
    queueHelper.dequeue();
    ParseTaskUtils.wait(getDataTaskB);
  }

  @Test
  public void testCancel() {
    ParseFile file = new ParseFile(new ParseFile.State.Builder().build());

    TaskQueueTestHelper queueHelper = new TaskQueueTestHelper(file.taskQueue);
    queueHelper.enqueue();

    List<Task<Void>> saveTasks = Arrays.asList(
        file.saveInBackground(),
        file.saveInBackground(),
        file.saveInBackground());

    List<Task<byte[]>> getDataTasks = Arrays.asList(
        file.getDataInBackground(),
        file.getDataInBackground(),
        file.getDataInBackground());

    file.cancel();
    queueHelper.dequeue();

    for (int i = 0; i < saveTasks.size(); i++ ) {
      assertTrue("Task #" + i + " was not cancelled", saveTasks.get(i).isCancelled());
    }
    for (int i = 0; i < getDataTasks.size(); i++ ) {
      assertTrue("Task #" + i + " was not cancelled", getDataTasks.get(i).isCancelled());
    }
  }

  // TODO(grantland): testEncode
  // TODO(grantland): testDecode
}
