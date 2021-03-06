package org.vaadin.johannest.loadtestdriver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;

public class LoadTestDriverBuilder {

    private static final int DEFAULT_PROXY_PORT = 8888;

    private String ipaddress;
    private String testPath;
    private String testName;
    private String resourcesPath;

    private int concurrentUsers;
    private int repeats;
    private int rampUpTime;
    private int proxyPort = DEFAULT_PROXY_PORT;

    private boolean testRefactoringEnabled;
    private boolean staticResourcesIngnoringEnabled;
    private int minPause=-1;
    private int maxPause=-1;
    private boolean headlessEnabled;

    public LoadTestDriverBuilder() {
        ipaddress = "127.0.0.1";
    }

    /**
     * In which ip address the test server is run. By default this is you local ip address
     *
     * @param ipaddress
     *         ip address
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withIpAddress(String ipaddress) {
        this.ipaddress = ipaddress;
        return this;
    }

    /**
     * How many virtual/concurrent users the test should use. By default this is 1.
     *
     * @param concurrentUsers
     *         concurrent Users
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withNumberOfConcurrentUsers(int concurrentUsers) {
        this.concurrentUsers = concurrentUsers;
        return this;
    }

    /**
     * How many times the test is repeated. By default this is 1.
     *
     * @param repeats
     *         repeats
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withRepeats(int repeats) {
        this.repeats = repeats;
        return this;
    }

    /**
     * Test's ramp up time to throw given amount of virtual users in. By default this is 1s.
     *
     * @param rampUpTime
     *         ramp Up Time
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withRampUpTimeInSeconds(int rampUpTime) {
        this.rampUpTime = rampUpTime;
        return this;
    }

    /**
     * Modify proxy's (used to record requests) port. By default this is 8888.
     *
     * @param proxyPort
     *         proxy Port
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
        return this;
    }

    /**
     * Path where the test script is saved.
     *
     * @param path
     *         path
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withPath(String path) {
        testPath = path;
        return this;
    }

    /**
     * Name for the test script. If not specified a random name is generated.
     *
     * @param name
     *         name
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withTestName(String name) {
        testName = name;
        return this;
    }

    /**
     * Path for test resources such as request bodies folder. If not specified, testPath + "/bodies" folder is used.
     *
     * @param path
     *         path
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withResourcesPath(String path) {
        resourcesPath = path;
        return this;
    }

    /**
     * Modify the test script by adding Vaadin related things to it
     *
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withTestRefactoring() {
        testRefactoringEnabled = true;
        return this;
    }

    /**
     * Ignore static resources such as .css, .js files and images
     *
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withStaticResourcesIngnoring() {
        staticResourcesIngnoringEnabled = true;
        return this;
    }


    /**
     * Add pauses between xhr requests (uniform random pause(minPause, maxPause)
     *
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withMinAndMaxPausesBetweenRequests(int minPause, int maxPause) {
        this.minPause = minPause;
        this.maxPause = maxPause;
        return this;
    }

    /**
     * Try going headless
     *
     * @return LoadTestDriverBuilder
     */
    public LoadTestDriverBuilder withHeadlessEnabled(boolean headlessEnabled) {
        this.headlessEnabled = headlessEnabled;
        return this;
    }

    public WebDriver build() {
        return build(Collections.emptyMap());
    }

    public WebDriver build(Map<String, String> additionalCapabilities) {
        final ArrayList<String> cliArgsCap = new ArrayList<>();
        cliArgsCap.add("--web-security=false");
        cliArgsCap.add("--load-images=false");
        cliArgsCap.add("--ignore-ssl-errors=true");
        cliArgsCap.add("--debug=true");
        cliArgsCap.add("--proxy=" + ipaddress + ":" + proxyPort);
        cliArgsCap.add("--proxy-type=http");

        final ArrayList<String> cliArgsCap2 = new ArrayList<>();
        cliArgsCap2.add("--logLevel=INFO");

        final DesiredCapabilities capabilities = DesiredCapabilities.phantomjs();
        capabilities.setCapability(PhantomJSDriverService.PHANTOMJS_CLI_ARGS, cliArgsCap);
        capabilities.setCapability(PhantomJSDriverService.PHANTOMJS_GHOSTDRIVER_CLI_ARGS, cliArgsCap2);
        for (Map.Entry<String,String> entry : additionalCapabilities.entrySet()) {
            capabilities.setCapability(entry.getKey(),entry.getValue());
        }

        LoadTestParameters loadTestParameters = new LoadTestParameters(concurrentUsers, rampUpTime, repeats,
                minPause, maxPause);

        final LoadTestDriver driver = new LoadTestDriver(capabilities, loadTestParameters, headlessEnabled);
        driver.setProxyHost(ipaddress);
        driver.setProxyPort(proxyPort);
        driver.setTempFilePath(testPath);
        driver.setResourcesPath(resourcesPath);
        driver.setTestName(testName);
        driver.setTestConfiguringEnabled(testRefactoringEnabled);
        driver.withStaticResourcesIngnoringEnabled(staticResourcesIngnoringEnabled);
        return driver;
    }
}