package org.camunda.rpa.client.core.robot;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.springframework.beans.factory.annotation.Value;
import org.apache.commons.io.FileUtils;
import org.camunda.rpa.client.core.pipe.RobotDirectoryScanner;
import org.camunda.rpa.client.data.RobotInput;
import org.camunda.rpa.client.data.entity.RobotHandlerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.configurationprocessor.json.JSONArray;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import reactor.core.publisher.Mono;
import org.springframework.http.*;

/**
 * This class will enable running robots in Robocorp cloud author : Shibin
 * Thomas
 */
@Service
public class RobocorpCloudService implements IRobotService {

	private static final Logger LOG = LoggerFactory.getLogger(RobocorpCloudService.class);

	@Autowired
	private WebClient webClient;

	@Autowired
	private RobotDirectoryScanner robotDirectoryScanner;

	@Value("${robot.cloud.api-key}")
	private String apiKey;

	@Value("${robot.cloud.api-url}")
	private String processApi;

	@Override
	public boolean runRobot(List<RobotInput> robotInputs, RobotHandlerConfig config) {
		try {
			return startRobotProcess(robotInputs, config);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	private boolean startRobotProcess(List<RobotInput> robotInputs, RobotHandlerConfig config) throws JSONException {
	//	String response = invokeRobot(robotInputs, config);
		String response = " {\"id\":\"8824d643-e215-4125-8cff-8c310f6ddcd1\",\"workItemIds\":[\"c5b4675a-0cb7-4eba-ad32-15aca7cb4cbd\"]}";
		JSONObject jsonObject = new JSONObject(response);
		String robotRunId = jsonObject.getString("id");
		String workItemId = new JSONArray(jsonObject.getString("workItemIds")).getString(0);

		boolean success = false;

		String output;
		for (; true;) {

			try {
				output = getWorkItems(robotRunId, config);
				Thread.sleep(5000);
				if (output.contains("FAILED")) {
					break;
				}
				success = isRobotCompleted(output);
				if (success) {
					collectResponseFile(output, workItemId, robotRunId, config);
					break;
				}
			} catch (Exception e) {
				break;
			}
		}
		return success;
	}

	private String invokeRobot(List<RobotInput> robotInputs, RobotHandlerConfig config) throws JSONException {
		String formattedInput = buildInput(robotInputs).toString();
		String uri = getRobotProcessUrl(config) + "/runs";
		ResponseEntity<String> response = webClient.method(HttpMethod.POST).uri(uri)
				.header("Authorization", getApiKey()).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(Mono.just(formattedInput), String.class).retrieve().toEntity(String.class).block();

		LOG.error(response.getBody());
		return response.getBody();
	}

	private JSONObject buildInput(List<RobotInput> robotInputs) throws JSONException {
		JSONObject formattedInput = new JSONObject();
		for (int i = 0; i < robotInputs.size(); i++) {
			formattedInput.put(robotInputs.get(i).getField(), robotInputs.get(i).getValue());
		}
		return formattedInput;
	}

	private String getWorkItems(String robotRunId, RobotHandlerConfig config) {
		String uri = getRobotProcessUrl(config) + "/runs/" + robotRunId + "/work-items";
		ResponseEntity<String> response = webClient.method(HttpMethod.GET).uri(uri).header("Authorization", getApiKey())
				.accept(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(Mono.empty(), String.class).retrieve().toEntity(String.class).block();

		return response.getBody();
	}

	private void collectResponseFile(String output, String workItemId, String robotRunId, RobotHandlerConfig config)
			throws JSONException, IOException {
		String jsonData = new JSONObject(output).getString("data");
		String data = jsonData != null ? new JSONArray(jsonData).getString(0) : null;
		String files = data != null ? new JSONObject(data).getString("files") : null;
		String fileIds = files != null ? new JSONArray(files).getString(0) : null;
		if (fileIds != null) {
			JSONObject jsonObject = new JSONObject(fileIds);
			String fileId = jsonObject.getString("id");
			String fileName = jsonObject.getString("name");

			String uri = getRobotProcessUrl(config) + "/work-items/" + workItemId + "/files/" + fileId + "/download";
			ResponseEntity<String> response = webClient.method(HttpMethod.GET).uri(uri)
					.header("Authorization", getApiKey()).accept(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(null).retrieve()
					.toEntity(String.class).block();

			LOG.error(response.getBody());
			downloadResponseFile(response, fileId, fileName, config);
		}
	}

	private void downloadResponseFile(ResponseEntity<String> response, String fileId, String fileName,
			RobotHandlerConfig config) throws MalformedURLException, IOException, JSONException {
		JSONObject jsonObject = new JSONObject(response.getBody());
		String outputUrl = jsonObject.getString("url");
		File outputDir = robotDirectoryScanner.getRobotFinalDirectory(config.getRobotName(),
				config.getWorkingDirName());
		FileUtils.copyURLToFile(new URL(outputUrl), new File(outputDir.getAbsolutePath() + File.separator + fileName));
	}

	private boolean isRobotCompleted(String result) {
		return result.contains("COMPLETED");
	}

	private String getApiKey() {
		return this.apiKey;
	}

	private String getRobotProcessUrl(RobotHandlerConfig config) {
		return this.processApi + "/" + config.getWorkspaceId() + "/processes/" + config.getProcessId();
	}
}
