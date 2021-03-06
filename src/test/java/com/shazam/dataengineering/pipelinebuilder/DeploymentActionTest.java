/*
 * Copyright 2015 Shazam Entertainment Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.shazam.dataengineering.pipelinebuilder;

import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.services.datapipeline.DataPipelineClient;
import com.amazonaws.services.datapipeline.model.*;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Run;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for the DeploymentAction class.
 * <p/>
 * Tests a lot of private methods, which is a bad practice, but
 * necessary to inject a mocked AWS client in this case, as well
 * as testing individual deployment pieces rather than walking
 * through the whole deployment in one step.
 */
public class DeploymentActionTest {
    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();

    @Test
    @WithoutJenkins
    public void getPipelineIdShouldReturnCorrectPipeline() throws Exception {
        String result = executeGetPipelineIdMethod("p1-this-is-a-test-pipeline-2");

        assertEquals("test1", result);
    }

    @Test
    @WithoutJenkins
    public void getPipelineIdShouldReturnEmptyId() throws Exception {
        String result = executeGetPipelineIdMethod("p1-this-is-another-pipeline-2");

        assertEquals("", result);
    }

    @Test
    @WithoutJenkins
    public void removeOldPipelineShouldGenerateInfoMessagesForSuccess() throws Exception {
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);
        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());
        DeletePipelineRequest request = new DeletePipelineRequest().withPipelineId("test");

        Field pipelineIdField = action.getClass().getDeclaredField("pipelineToRemoveId");
        pipelineIdField.setAccessible(true);
        pipelineIdField.set(action, "test");
        Method method = action.getClass().getDeclaredMethod("removeOldPipeline", DataPipelineClient.class);
        method.setAccessible(true);

        method.invoke(action, dataPipelineClient);
        verify(dataPipelineClient).deletePipeline(request);
        assertTrue(action.getClientMessages().get(0).contains("[INFO]"));
        assertFalse(action.getClientMessages().get(0).contains("[WARN]"));
    }

    @Test
    @WithoutJenkins
    public void createNewPipelineShouldReturnPipelineId() throws Exception {
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);
        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());
        CreatePipelineResult createPipelineResult = new CreatePipelineResult().withPipelineId("test12345");
        when(dataPipelineClient.createPipeline(any(CreatePipelineRequest.class))).thenReturn(createPipelineResult);

        Field pipelineFileField = action.getClass().getDeclaredField("pipelineFile");
        pipelineFileField.setAccessible(true);
        pipelineFileField.set(action, "p1-test-pipeline-name-34.json");
        Method method = action.getClass().getDeclaredMethod("createNewPipeline", DataPipelineClient.class);
        method.setAccessible(true);

        String result = (String) method.invoke(action, dataPipelineClient);

        assertEquals("test12345", result);
    }

    @Test
    @WithoutJenkins
    public void validateNewPipelineShouldSaveWarningAndErrorMessages() throws Exception {
        String pipelineId = "test1234";
        String json = new FilePath(new File("src/test/resources/pipeline3.json")).readToString();
        PipelineObject pipeline = new PipelineObject(json);

        ValidatePipelineDefinitionRequest validationRequest = new ValidatePipelineDefinitionRequest()
                .withPipelineId(pipelineId).withPipelineObjects(pipeline.getAWSObjects());
        ValidatePipelineDefinitionResult validationResponse = new ValidatePipelineDefinitionResult()
                .withValidationWarnings(
                        new ValidationWarning().withWarnings("1", "2", "3")
                )
                .withValidationErrors(
                        new ValidationError().withErrors("4", "5"),
                        new ValidationError().withErrors("6")
                ).withErrored(false);
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);
        when(dataPipelineClient.validatePipelineDefinition(validationRequest)).thenReturn(validationResponse);

        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());

        Field pipelineFileField = action.getClass().getDeclaredField("pipelineObject");
        pipelineFileField.setAccessible(true);
        pipelineFileField.set(action, pipeline);
        Method method = action.getClass().getDeclaredMethod("validateNewPipeline", String.class, DataPipelineClient.class);
        method.setAccessible(true);

        method.invoke(action, pipelineId, dataPipelineClient);

        assertEquals(7, action.getClientMessages().size());
        assertTrue(action.getClientMessages().get(0).contains("[ERROR]"));
        assertTrue(action.getClientMessages().get(1).contains("[ERROR]"));
        assertTrue(action.getClientMessages().get(2).contains("[ERROR]"));
        assertTrue(action.getClientMessages().get(3).contains("[WARN]"));
        assertTrue(action.getClientMessages().get(4).contains("[WARN]"));
        assertTrue(action.getClientMessages().get(5).contains("[WARN]"));
    }

    @Test(expected = InvocationTargetException.class) // Caused by a DeploymentException
    @WithoutJenkins
    public void validateNewPipelineShouldThrowExceptionWhenValidationFails() throws Exception {
        String pipelineId = "test1234";
        ArrayList<com.amazonaws.services.datapipeline.model.PipelineObject> pipelineList =
                new ArrayList<com.amazonaws.services.datapipeline.model.PipelineObject>();
        PipelineObject pipeline = mock(PipelineObject.class);
        when(pipeline.getAWSObjects()).thenReturn(pipelineList);

        ValidatePipelineDefinitionRequest validationRequest = new ValidatePipelineDefinitionRequest()
                .withPipelineId(pipelineId).withPipelineObjects(pipelineList);
        ValidatePipelineDefinitionResult validationResponse = new ValidatePipelineDefinitionResult()
                .withValidationWarnings(
                        new ValidationWarning().withWarnings("1", "2", "3")
                )
                .withValidationErrors(
                        new ValidationError().withErrors("4", "5"),
                        new ValidationError().withErrors("6")
                ).withErrored(true);
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);
        when(dataPipelineClient.validatePipelineDefinition(validationRequest)).thenReturn(validationResponse);

        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());

        Field pipelineFileField = action.getClass().getDeclaredField("pipelineObject");
        pipelineFileField.setAccessible(true);
        pipelineFileField.set(action, pipeline);
        Method method = action.getClass().getDeclaredMethod("validateNewPipeline", String.class, DataPipelineClient.class);
        method.setAccessible(true);

        method.invoke(action, pipelineId, dataPipelineClient);
    }

    @Test
    @WithoutJenkins
    public void uploadNewPipelineShouldCallPutPipeline() throws Exception {
        String pipelineId = "test1234";
        ArrayList<com.amazonaws.services.datapipeline.model.PipelineObject> pipelineList =
                new ArrayList<com.amazonaws.services.datapipeline.model.PipelineObject>();
        PipelineObject pipeline = mock(PipelineObject.class);
        when(pipeline.getAWSObjects()).thenReturn(pipelineList);

        PutPipelineDefinitionRequest putRequest = new PutPipelineDefinitionRequest()
                .withPipelineId(pipelineId).withPipelineObjects(pipelineList);
        PutPipelineDefinitionResult putResult = new PutPipelineDefinitionResult().withErrored(false);
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);
        when(dataPipelineClient.putPipelineDefinition(putRequest)).thenReturn(putResult);

        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());

        Field pipelineFileField = action.getClass().getDeclaredField("pipelineObject");
        pipelineFileField.setAccessible(true);
        pipelineFileField.set(action, pipeline);
        Method method = action.getClass().getDeclaredMethod("uploadNewPipeline", String.class, DataPipelineClient.class);
        method.setAccessible(true);

        method.invoke(action, pipelineId, dataPipelineClient);

        verify(pipeline).getAWSObjects();
        verify(dataPipelineClient).putPipelineDefinition(any(PutPipelineDefinitionRequest.class));
    }

    @Test
    @WithoutJenkins
    public void activateNewPipelineShouldCallActivatePipeline() throws Exception {
        String pipelineId = "test1234";
        ActivatePipelineRequest activateRequest = new ActivatePipelineRequest()
                .withPipelineId(pipelineId);
        ActivatePipelineResult activateResult = new ActivatePipelineResult();
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);
        when(dataPipelineClient.activatePipeline(activateRequest)).thenReturn(activateResult);

        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());

        Method method = action.getClass().getDeclaredMethod("activateNewPipeline", String.class, DataPipelineClient.class);
        method.setAccessible(true);

        method.invoke(action, pipelineId, dataPipelineClient);

        verify(dataPipelineClient).activatePipeline(any(ActivatePipelineRequest.class));
    }

    @Test(expected = InvocationTargetException.class)
    @WithoutJenkins
    public void failingS3DeploymentShouldThrowDeploymentException() throws Exception {
        testFolder.newFolder("scripts");
        testFolder.newFile("scripts/script.pig");

        HashMap<S3Environment, String> s3Urls = new HashMap<S3Environment, String>();
        s3Urls.put(new S3Environment("test.json", "script.pig"), "s3://bucket/");

        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                s3Urls,
                new AnonymousAWSCredentials());

        Field pipelineFileField = action.getClass().getDeclaredField("pipelineFile");
        pipelineFileField.setAccessible(true);
        pipelineFileField.set(action, "test.json");
        Method method = action.getClass().getDeclaredMethod("deployScriptsToS3");
        method.setAccessible(true);

        method.invoke(action);
    }

    @Test
    public void writingReportShouldCreateJsonFile() throws Exception {
        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());

        Date date = new Date();

        Method method = action.getClass().getDeclaredMethod("writeReport", Date.class, String.class, Boolean.TYPE);
        method.setAccessible(true);

        method.invoke(action, date, "test-1234", true);

        File logFile = new File(testFolder.getRoot(), "deployment.log");
        assertTrue(logFile.exists());

        List<String> jsonContent = Files.readAllLines(logFile.toPath(), Charset.defaultCharset());
        assertEquals(1, jsonContent.size());

        JSONParser jsonParser = new JSONParser();
        JSONObject log = (JSONObject) jsonParser.parse(jsonContent.get(0));
        JSONArray deployments = (JSONArray) log.get("deployments");
        JSONObject deployment = (JSONObject) deployments.get(0);

        assertEquals(String.valueOf(date.getTime()), deployment.get("date").toString());
        assertEquals("SYSTEM", deployment.get("username").toString());
        assertEquals("true", deployment.get("status").toString());
        assertEquals("test-1234", deployment.get("pipelineId"));
    }

    private String executeGetPipelineIdMethod(String pipelineFileName)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        List<PipelineIdName> pipelineList = new ArrayList<PipelineIdName>();
        pipelineList.add(new PipelineIdName().withId("test1").withName("p1-this-is-a-test-pipeline-1"));
        pipelineList.add(new PipelineIdName().withId("test2").withName("d2-this-is-a-test-pipeline-1"));
        DeploymentAction action = new DeploymentAction(
                getMockAbstractBuild(),
                new HashMap<S3Environment, String>(),
                new AnonymousAWSCredentials());
        DataPipelineClient client = getMockDataPipelineClient(pipelineList);

        Method method = action.getClass().getDeclaredMethod("getPipelineId", String.class, DataPipelineClient.class);
        method.setAccessible(true);

        return (String) method.invoke(action, pipelineFileName, client);
    }

    private DataPipelineClient getMockDataPipelineClient(List<PipelineIdName> pipelineList) {
        ListPipelinesResult listPipelinesResult = mock(ListPipelinesResult.class);
        DataPipelineClient dataPipelineClient = mock(DataPipelineClient.class);

        when(dataPipelineClient.listPipelines(any(ListPipelinesRequest.class))).thenReturn(listPipelinesResult);
        when(listPipelinesResult.getPipelineIdList()).thenReturn(pipelineList);

        return dataPipelineClient;
    }

    private AbstractBuild getMockAbstractBuild() {
        AbstractBuild build = mock(AbstractBuild.class);
        AbstractProject project = mock(AbstractProject.class);

        when(build.getProject()).thenReturn(project);
        when(project.getName()).thenReturn("test");
        when(build.getArtifacts()).thenReturn(new ArrayList<Run.Artifact>());
        when(build.getArtifactsDir()).thenReturn(testFolder.getRoot());

        return build;
    }
}
