package org.kurento.test.browser;

import static org.kurento.commons.PropertiesManager.getProperty;
import static org.kurento.test.TestConfiguration.DOCKER_HUB_CONTAINER_NAME_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_HUB_CONTAINER_NAME_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_HUB_IMAGE_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_HUB_IMAGE_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_CHROME_DEBUG_IMAGE_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_CHROME_DEBUG_IMAGE_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_CHROME_IMAGE_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_CHROME_IMAGE_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_FIREFOX_DEBUG_IMAGE_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_FIREFOX_DEBUG_IMAGE_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_FIREFOX_IMAGE_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_NODE_FIREFOX_IMAGE_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_VNCRECORDER_CONTAINER_NAME_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_VNCRECORDER_CONTAINER_NAME_PROPERTY;
import static org.kurento.test.TestConfiguration.DOCKER_VNCRECORDER_IMAGE_DEFAULT;
import static org.kurento.test.TestConfiguration.DOCKER_VNCRECORDER_IMAGE_PROPERTY;
import static org.kurento.test.TestConfiguration.SELENIUM_MAX_DRIVER_ERROR_DEFAULT;
import static org.kurento.test.TestConfiguration.SELENIUM_MAX_DRIVER_ERROR_PROPERTY;
import static org.kurento.test.TestConfiguration.SELENIUM_RECORD_DEFAULT;
import static org.kurento.test.TestConfiguration.SELENIUM_RECORD_PROPERTY;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.kurento.commons.exception.KurentoException;
import org.kurento.commons.net.RemoteService;
import org.kurento.test.base.KurentoClientWebPageTest;
import org.kurento.test.docker.Docker;
import org.kurento.test.services.KurentoServicesTestHelper;
import org.openqa.grid.common.exception.GridException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class DockerBrowserManager {

	private static final long CONTAINER_CREATION_TIMEOUT = 20000;

	private static Logger log = LoggerFactory
			.getLogger(DockerBrowserManager.class);

	private class DockerBrowser {

		private static final int WAIT_POOL_TIME = 1000;
		private String id;
		private String browserContainerName;
		private String vncrecorderContainerName;
		private String browserContainerIp;
		private DesiredCapabilities capabilities;
		private RemoteWebDriver driver;

		public DockerBrowser(String id, DesiredCapabilities capabilities) {
			this.id = id;
			this.capabilities = capabilities;
			calculateContainerNames();
		}

		private void calculateContainerNames() {

			browserContainerName = id;

			vncrecorderContainerName = browserContainerName + "-"
					+ getProperty(DOCKER_VNCRECORDER_CONTAINER_NAME_PROPERTY,
							DOCKER_VNCRECORDER_CONTAINER_NAME_DEFAULT);

			if (docker.isRunningInContainer()) {

				String containerName = docker.getContainerName();

				browserContainerName = containerName + "-"
						+ browserContainerName;
				vncrecorderContainerName = containerName + "-"
						+ vncrecorderContainerName;
			}
		}

		private void waitForNodeRegisteredInHub() {

			while (true) {

				try {

					JsonObject result = curl(
							hubUrl + "/grid/api/proxy?id=http://"
									+ browserContainerIp + ":5555");

					if (result.get("success").getAsBoolean()) {
						log.info("Capabilities of container {}: {}",
								browserContainerName, result.get("request")
										.getAsJsonObject().get("capabilities"));
						return;
					} else {
						log.debug(
								"Node {} not registered in hub. Waiting 1s...",
								id);
					}

					waitPoolTime();

				} catch (MalformedURLException e) {
					throw new Error(e);
				} catch (IOException e) {
					log.debug("Hub is not ready. Waiting 1s...");
					waitPoolTime();
				}
			}
		}

		private void waitPoolTime() {
			try {
				Thread.sleep(WAIT_POOL_TIME);
			} catch (InterruptedException e1) {
			}
		}

		private JsonObject curl(String urlString)
				throws MalformedURLException, IOException {
			URL url = new URL(urlString);

			URLConnection connection = url.openConnection();

			Reader is = new BufferedReader(
					new InputStreamReader(connection.getInputStream()));

			JsonObject result = new GsonBuilder().create().fromJson(is,
					JsonObject.class);
			return result;
		}

		public void create() {

			String nodeImageId = calculateBrowserImageName(capabilities);

			BrowserType type = BrowserType
					.valueOf(capabilities.getBrowserName().toUpperCase());

			docker.startAndWaitNode(id, type, browserContainerName, nodeImageId,
					dockerHubIp);

			browserContainerIp = docker.inspectContainer(browserContainerName)
					.getNetworkSettings().getIpAddress();

			waitForNodeRegisteredInHub();

			createAndWaitRemoteDriver(hubUrl + "/wd/hub", capabilities);

			log.debug("RemoteWebDriver for browser {} created", id);

			if (record) {
				createVncRecorderContainer();
			}
		}

		private void createAndWaitRemoteDriver(String driverUrl,
				DesiredCapabilities capabilities) {

			log.debug("Creating remote driver for browser {} in hub {}", id,
					driverUrl);

			int timeoutSeconds = getProperty(SELENIUM_MAX_DRIVER_ERROR_PROPERTY,
					SELENIUM_MAX_DRIVER_ERROR_DEFAULT);

			long timeoutMs = System.currentTimeMillis()
					+ TimeUnit.SECONDS.toMillis(timeoutSeconds);

			do {
				try {

					RemoteWebDriver rDriver = new RemoteWebDriver(
							new URL(driverUrl), capabilities);

					SessionId sessionId = rDriver.getSessionId();
					String nodeIp = obtainBrowserNodeIp(sessionId);

					if (!nodeIp.equals(browserContainerIp)) {
						log.warn("*****************************************");
						log.warn(
								"Browser {} is not created in its container. Container IP: {} Browser IP:{}",
								id, browserContainerIp, nodeIp);
						log.warn("*****************************************");
					}

					log.debug(
							"Created selenium session {} for browser {} in node {}",
							sessionId, id, nodeIp);

					driver = rDriver;

				} catch (MalformedURLException e) {
					throw new Error("Hub URL is malformed", e);

				} catch (GridException | WebDriverException t) {

					driver = null;
					// Check timeout
					if (System.currentTimeMillis() > timeoutMs) {
						throw t;
					}

					log.debug(
							"Exception creating RemoteWebDriver for browser \"{}\". Retrying...",
							id);

					// Poll time
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			} while (driver == null);
		}

		private String obtainBrowserNodeIp(SessionId sessionId) {

			try {

				JsonObject result = curl(
						hubUrl + "/grid/api/testsession?session=" + sessionId);

				String nodeIp = (String) result.get("proxyId").getAsString();
				return nodeIp.substring(7, nodeIp.length()).split(":")[0];

			} catch (IOException e) {
				log.warn("Exception while trying to obtain node Ip. {}:{}",
						e.getClass().getName(), e.getMessage());
				return null;
			}

		}

		private void createVncRecorderContainer() {

			try {

				try {
					RemoteService.waitForReady(browserContainerIp, 5900, 10,
							TimeUnit.SECONDS);
				} catch (TimeoutException e) {
					throw new RuntimeException(
							"Timeout when connecting to browser VNC");
				}

				String vncrecordImageId = getProperty(
						DOCKER_VNCRECORDER_IMAGE_PROPERTY,
						DOCKER_VNCRECORDER_IMAGE_DEFAULT);

				if (docker.existsContainer(vncrecorderContainerName)) {
					throw new KurentoException("Vncrecorder container '"
							+ vncrecorderContainerName + "' already exists");
				}

				String secretFile = createSecretFile();

				docker.pullImageIfNecessary(vncrecordImageId);

				String videoFile = Paths
						.get(KurentoClientWebPageTest
								.getDefaultOutputFile("-" + id + "-record.flv"))
						.toAbsolutePath().toString();

				log.debug(
						"Creating container {} for recording video from browser {} in file {}",
						vncrecorderContainerName, browserContainerName,
						videoFile);

				CreateContainerCmd createContainerCmd = docker.getClient()
						.createContainerCmd(vncrecordImageId)
						.withName(vncrecorderContainerName).withCmd("-o",
								videoFile, "-P", secretFile, browserContainerIp,
								"5900");

				docker.mountDefaultFolders(createContainerCmd);

				createContainerCmd.exec();

				docker.startContainer(vncrecorderContainerName);

				log.debug("Container {} started...", vncrecorderContainerName);

			} catch (Exception e) {
				log.warn("Exception creating vncRecorder container");
			}
		}

		public RemoteWebDriver getRemoteWebDriver() {
			return driver;
		}

		public void close() {

			downloadLogsForContainer(browserContainerName, id);

			downloadLogsForContainer(vncrecorderContainerName,
					id + "-recorder");

			docker.stopAndRemoveContainers(vncrecorderContainerName,
					browserContainerName);

		}
	}

	private Docker docker = Docker.getSingleton();

	private AtomicInteger numBrowsers = new AtomicInteger();
	private CountDownLatch hubStarted = new CountDownLatch(1);
	private String dockerHubIp;
	private String hubContainerName;
	private String hubUrl;

	private ConcurrentMap<String, DockerBrowser> browsers = new ConcurrentHashMap<>();

	private boolean record;

	private Path downloadLogsPath;

	public DockerBrowserManager() {
		docker = Docker.getSingleton();
		record = getProperty(SELENIUM_RECORD_PROPERTY, SELENIUM_RECORD_DEFAULT);
		calculateHubContainerName();
	}

	public void setDownloadLogsPath(Path path) {
		this.downloadLogsPath = path;
	}

	private void calculateHubContainerName() {
		hubContainerName = getProperty(DOCKER_HUB_CONTAINER_NAME_PROPERTY,
				DOCKER_HUB_CONTAINER_NAME_DEFAULT);

		if (docker.isRunningInContainer()) {

			String containerName = docker.getContainerName();

			hubContainerName = containerName + "-" + hubContainerName;
		}
	}

	public RemoteWebDriver createDockerDriver(String id,
			DesiredCapabilities capabilities) throws MalformedURLException {

		DockerBrowser browser = new DockerBrowser(id, capabilities);

		if (browsers.putIfAbsent(id, browser) != null) {
			throw new KurentoException(
					"Browser with id " + id + " already exists");
		}

		boolean firstBrowser = numBrowsers.incrementAndGet() == 1;

		startHub(firstBrowser);

		browser.create();

		return browser.getRemoteWebDriver();
	}

	public void closeDriver(String id) {

		DockerBrowser browser = browsers.remove(id);

		if (browser == null) {
			log.warn("Browser " + id + " does not exists");
			return;
		}

		browser.close();

		if (numBrowsers.decrementAndGet() == 0) {
			closeHub();
		}
	}

	private void closeHub() {

		if (hubContainerName == null) {
			log.warn("Trying to close Hub, but it is not created");
			return;
		}

		downloadLogsForContainer(hubContainerName, "hub");
		docker.stopAndRemoveContainers(hubContainerName);

		dockerHubIp = null;
		hubUrl = null;
		hubStarted = new CountDownLatch(1);
	}

	private void startHub(boolean firstBrowser) {

		if (firstBrowser) {

			log.debug("Creating hub...");

			String hubImageId = getProperty(DOCKER_HUB_IMAGE_PROPERTY,
					DOCKER_HUB_IMAGE_DEFAULT);

			dockerHubIp = docker.startAndWaitHub(hubContainerName, hubImageId,
					CONTAINER_CREATION_TIMEOUT);

			hubUrl = "http://" + dockerHubIp + ":4444";

			hubStarted.countDown();

		} else {

			if (hubStarted.getCount() != 0) {

				log.debug("Waiting for hub...");

				try {
					hubStarted.await();
				} catch (InterruptedException e) {
					throw new RuntimeException(
							"InterruptedException while waiting to hub creation");
				}
			}
		}
	}

	private String createSecretFile() throws IOException {
		Path secretFile = Paths
				.get(KurentoServicesTestHelper.getTestDir() + "vnc-passwd");

		try (BufferedWriter bw = Files.newBufferedWriter(secretFile,
				StandardCharsets.UTF_8)) {
			bw.write("secret");
		}

		return secretFile.toAbsolutePath().toString();
	}

	private String calculateBrowserImageName(DesiredCapabilities capabilities) {

		String browserName = capabilities.getBrowserName();

		if (browserName.equals(DesiredCapabilities.chrome().getBrowserName())) {

			// Chrome
			if (record) {
				return getProperty(DOCKER_NODE_CHROME_DEBUG_IMAGE_PROPERTY,
						DOCKER_NODE_CHROME_DEBUG_IMAGE_DEFAULT);
			} else {
				return getProperty(DOCKER_NODE_CHROME_IMAGE_PROPERTY,
						DOCKER_NODE_CHROME_IMAGE_DEFAULT);
			}

		} else if (browserName
				.equals(DesiredCapabilities.firefox().getBrowserName())) {

			// Firefox
			if (record) {
				return getProperty(DOCKER_NODE_FIREFOX_DEBUG_IMAGE_PROPERTY,
						DOCKER_NODE_FIREFOX_DEBUG_IMAGE_DEFAULT);
			} else {
				return getProperty(DOCKER_NODE_FIREFOX_IMAGE_PROPERTY,
						DOCKER_NODE_FIREFOX_IMAGE_DEFAULT);
			}

		} else {
			throw new RuntimeException("Browser " + browserName
					+ " is not supported currently for Docker scope");
		}
	}

	private void downloadLogsForContainer(String container, String logName) {

		if (docker.existsContainer(container) && downloadLogsPath != null) {

			try {

				Path logFile = downloadLogsPath.resolve(logName + ".log");

				log.debug("Downloading log for container {} in file {}",
						container, logFile.toAbsolutePath());

				docker.downloadLog(container, logFile);

			} catch (IOException e) {
				log.warn("Exception writing logs for container {}", container,
						e);
			}
		}
	}

}
