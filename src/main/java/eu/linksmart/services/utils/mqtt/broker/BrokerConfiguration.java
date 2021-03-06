package eu.linksmart.services.utils.mqtt.broker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.linksmart.services.utils.configuration.Configurator;
import eu.linksmart.services.utils.constants.Const;
import eu.linksmart.services.utils.function.Utils;
import io.swagger.client.ApiClient;
import io.swagger.client.api.ScApi;
import io.swagger.client.model.Service;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.omg.CORBA.portable.UnknownException;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by José Ángel Carvajal on 23.09.2016 a researcher of Fraunhofer FIT.
 */
public class BrokerConfiguration {
    private static final String SC_API_NAME = "MQTT";
    // id of the configuration
    protected String id = UUID.randomUUID().toString();
    // alias (human readable) name of the broker
    protected String alias = "default", realProfile = "default";
    // the default config is set
    private static boolean _defaultSet = false;
    // hostname or IP of the broker
    protected static String _hostname = "localhost";
    protected String hostname = _hostname;
    // port of the broker
    protected int _port = 1883;
    protected int port = _port;
    // secure port of the broker
    protected int _securePort = 8883;
    protected int securePort = _securePort;
    // if the persistence is file base, otherwise is memory based
    protected boolean filePersistence = false;
    // discard own messages
    protected boolean autoBlacklisting = false;
    // default subscription quality of service I[0,2]
    protected int subQoS = 0;
    // default subscription quality of service I[0,2]
    protected int pubQoS = 0;
    // default retain policy
    protected boolean retainPolicy = false;
    // define security configuration, otherwise null no security
    protected BrokerSecurityConfiguration secConf = null;
    // keep alive configuration
    protected int keepAlive = 60000;
    // time out alive configuration
    protected int timeOut = 60000;
    //no. reconnect tries
    protected int noTries=10;
    //reconnect waiting time
    protected int reconnectWaitingTime=60000;
    //maximum messages can be one the messaging queue waiting to be sent
    protected int maxInFlightMessages=10;
    //version of the Mqtt protocol possible are 3.1 or 3.1.1. Default goes to 3.1.1, if fails then 3.1. Version 3 is a non-existing dummy enumeration member
    private MqttVersion version = MqttVersion.DEFAULT;
    // define if the reconnection is managed by paho (true) or by the BrokerService (false)
    private boolean automaticReconnect = true;
    // define if the broker must remember the connection state
    private boolean cleanSession = false;
    // will (testament) of the client left in the broker in case the connection to it is completely lost
    protected String will = null;
    // topic of the will (see above)
    protected String willTopic = null;
    // user for connecting to the broker
    private String user = null;
    // password of the user (above) for connecting to the broker
    private String password = null;
    // supported protocols
    private static Set<String> protocols = new HashSet<>(Arrays.asList("tcp", "mqtt", "mqtt")), secureProtocols = new HashSet<>(Arrays.asList("tls", "ssl", "mqtts"));

    static private boolean loaded =false;
    @JsonIgnore
    private transient MqttConnectOptions mqttOptions = null;
    @JsonIgnore
    private transient static Configurator conf = Configurator.getDefaultConfig();
    @JsonIgnore
    private transient static final ConcurrentMap<String, BrokerConfiguration> aliasBrokerConf = new ConcurrentHashMap<>();

    // using host, port and protocol form Service Catalog
    static private transient ScApi SCclient = null;
    static {

        if(Utils.isRestAvailable(conf.getString(Const.LINKSMART_SERVICE_CATALOG_ENDPOINT))){
            ApiClient apiClient = new ApiClient();
            apiClient.setBasePath(conf.getString(Const.LINKSMART_SERVICE_CATALOG_ENDPOINT));
            SCclient = new ScApi(apiClient);
        }


    }
    public static Map<String,BrokerConfiguration> loadConfigurations() throws UnknownError{
        try {
            if(!loaded) {
                List aux = conf.getList(Const.BROKERS_ALIAS);
                List<String> aliases = new ArrayList<>();
                aliases.addAll(aux);
                if (aliasBrokerConf.isEmpty() || !aliasBrokerConf.keySet().containsAll(aliases))
                    aliases.forEach(i -> aliasBrokerConf.putIfAbsent(i, loadConfiguration(i)));
                loaded=true;
            }
            return aliasBrokerConf;
        }catch (Exception e){
            throw new UnknownError(e.getMessage());
        }


    }

    static public void put(String alias,BrokerConfiguration brokerConfiguration){
        aliasBrokerConf.putIfAbsent(alias,brokerConfiguration);
    }
    static public boolean contains(String alias){
        return aliasBrokerConf.containsKey(alias);
    }
    static public BrokerConfiguration loadConfiguration(String alias){
       BrokerConfiguration brokerConfiguration = new BrokerConfiguration();

       return loadConfiguration(alias,brokerConfiguration);
    }
    static public MqttConnectOptions initMqttOptions(BrokerConfiguration brokerConf) throws InternalError, UnknownError{
        MqttConnectOptions mqttOptions;
        try {
            mqttOptions = new MqttConnectOptions();

            mqttOptions.setConnectionTimeout(brokerConf.timeOut/1000);
            mqttOptions.setKeepAliveInterval(brokerConf.keepAlive/1000);
            mqttOptions.setMqttVersion(brokerConf.version.ordinal());
            mqttOptions.setAutomaticReconnect(brokerConf.automaticReconnect);
            mqttOptions.setCleanSession(brokerConf.cleanSession);
            if(brokerConf.user!=null ) {
                mqttOptions.setUserName(brokerConf.user);
                mqttOptions.setPassword(brokerConf.password.toCharArray());
            }
            if(brokerConf.will!=null&& brokerConf.willTopic!=null)
                mqttOptions.setWill(brokerConf.willTopic, brokerConf.will.getBytes(),2,false);


          //  mqttOptions.setServerURIs();
          //  mqttOptions.setSSLProperties();
            if(brokerConf.secConf!=null && !"".equals(brokerConf.secConf.CApath) && !"".equals(brokerConf.secConf.clientCertificatePath) && !"".equals(brokerConf.secConf.keyPath) ) {
                SSLSocketFactory socketFactory;
                try {
                    socketFactory = Utils.getSocketFactory(brokerConf.secConf.CApath, brokerConf.secConf.clientCertificatePath,brokerConf.secConf.keyPath, brokerConf.secConf.CAPassword,brokerConf.secConf.clientCertificatePassword,brokerConf.secConf.keyPassword);
                } catch (Exception e) {
                    throw new InternalError(e);
                }
                mqttOptions.setSocketFactory(socketFactory);
            }

        }catch (Exception e){

            throw new UnknownError(e.getMessage());
        }

        return mqttOptions;
    }
    static protected BrokerConfiguration loadConfiguration(String alias, BrokerConfiguration brokerConf){
        try {
            if(aliasBrokerConf.containsKey(alias))
                return aliasBrokerConf.get(alias);

            String aux = "".equals(alias)|| alias==null ? "":"_" + alias;

            brokerConf = aliasBrokerConf.getOrDefault(alias,brokerConf);

            brokerConf.alias=alias;
            brokerConf.hostname = getString(Const.DEFAULT_HOSTNAME, aux,brokerConf.hostname);
            brokerConf.port = getInt(Const.DEFAULT_PORT, aux, brokerConf.port);
            brokerConf.securePort = getInt(Const.DEFAULT_PORT_SECURE, aux, brokerConf.securePort);
            brokerConf.filePersistence = getBoolean(Const.DEFAULT_CONNECTION_PERSISTENCY, aux, brokerConf.filePersistence);
            brokerConf.pubQoS = getInt(Const.DEFAULT_PUBLISH_QOS, aux,  brokerConf.pubQoS);
            brokerConf.subQoS = getInt(Const.DEFAULT_SUBSCRIPTION_QoS, aux, brokerConf.subQoS);
            brokerConf.retainPolicy = getBoolean(Const.DEFAULT_RETAIN_POLICY, aux,  brokerConf.retainPolicy);
            brokerConf.noTries = getInt(Const.RECONNECTION_TRY, aux, brokerConf.noTries);
            brokerConf.reconnectWaitingTime = getInt(Const.RECONNECTION_MQTT_RETRY_TIME,  aux,  brokerConf.reconnectWaitingTime);
            brokerConf.pubQoS = getInt(Const.DEFAULT_PUBLISH_QOS, aux,  brokerConf.pubQoS);
            brokerConf.timeOut = getInt(BrokerServiceConst.CONNECTION_MQTT_CONNECTION_TIMEOUT, aux,  brokerConf.timeOut);
            brokerConf.keepAlive = getInt(BrokerServiceConst.CONNECTION_MQTT_KEEP_ALIVE_TIMEOUT, aux, brokerConf.keepAlive);
            brokerConf.maxInFlightMessages = getInt(BrokerServiceConst.MAX_IN_FLIGHT, aux,  brokerConf.maxInFlightMessages);
            brokerConf.version =  MqttVersion.valueOf(getString(BrokerServiceConst.MQTT_VERSION, aux,  MqttVersion.DEFAULT.toString()));
            brokerConf.automaticReconnect = getBoolean(BrokerServiceConst.AUTOMATIC_RECONNECT,aux,  brokerConf.automaticReconnect);
            brokerConf.cleanSession = getBoolean(BrokerServiceConst.CLEAN_SESSION,aux,  brokerConf.cleanSession);
            brokerConf.autoBlacklisting = getBoolean(BrokerServiceConst.AUTOBLACKLISTING,aux,  brokerConf.autoBlacklisting);
            if(conf.containsKeyAnywhere(BrokerServiceConst.USER + aux)&& conf.containsKeyAnywhere(BrokerServiceConst.USER )) {
                brokerConf.user = getString(BrokerServiceConst.USER, aux,  brokerConf.user);
                brokerConf.password = getString(BrokerServiceConst.PASSWORD, aux,  brokerConf.password);
            }

            if ((conf.containsKeyAnywhere(Const.CERTIFICATE_BASE_SECURITY) ||  conf.containsKeyAnywhere(Const.CERTIFICATE_BASE_SECURITY + aux))&& getBoolean(Const.CERTIFICATE_BASE_SECURITY, aux,  brokerConf.secConf != null)) {
                brokerConf.secConf = brokerConf.getInitSecurityConfiguration();
                brokerConf.secConf.CApath = getString(Const.CA_CERTIFICATE_PATH, aux,  brokerConf.secConf.CApath);
                brokerConf.secConf.clientCertificatePath = getString(Const.CERTIFICATE_FILE_PATH, aux,  brokerConf.secConf.clientCertificatePath);
                brokerConf.secConf.keyPath = getString(Const.KEY_FILE_PATH, aux,  brokerConf.secConf.keyPath);
                brokerConf.secConf.CAPassword = getString(Const.CA_CERTIFICATE_PASSWORD, aux,  brokerConf.secConf.CAPassword);
                brokerConf.secConf.clientCertificatePassword = getString(Const.CERTIFICATE_PASSWORD, aux,  brokerConf.secConf.clientCertificatePassword);
                brokerConf.secConf.keyPassword = getString(Const.KEY_PASSWORD, aux,  brokerConf.secConf.keyPassword);
            }

            linksmartServiceCatalogOverwrite(brokerConf, brokerConf.alias);

            return brokerConf;
        }catch (Exception e){
            throw new UnknownError(e.getMessage());
        }
    }
    static protected BrokerConfiguration loadConfiguration(BrokerConfiguration brokerConf,  BrokerConfiguration reference){
        if(reference == null)
            throw  new UnknownException(new Exception("The provided broker configuration reference is not exists or it's null"));
        try {
            brokerConf.hostname = reference.hostname;
            brokerConf.port = reference.port;
            brokerConf.securePort = reference.securePort;
            brokerConf.filePersistence = reference.filePersistence;
            brokerConf.pubQoS = reference.pubQoS;
            brokerConf.subQoS = reference.subQoS;
            brokerConf.retainPolicy = reference.retainPolicy;
            brokerConf.noTries = reference.noTries;
            brokerConf.reconnectWaitingTime = reference.reconnectWaitingTime;
            brokerConf.pubQoS = reference.pubQoS;
            brokerConf.timeOut = reference.timeOut;
            brokerConf.keepAlive = reference.keepAlive;
            brokerConf.maxInFlightMessages = reference.maxInFlightMessages;
            brokerConf.version = reference.version;
            brokerConf.automaticReconnect = reference.automaticReconnect;
            brokerConf.cleanSession = reference.cleanSession;
            brokerConf.user = reference.user;
            brokerConf.password = reference.password;
            brokerConf.autoBlacklisting = reference.autoBlacklisting;

            if (reference.secConf!=null) {
                brokerConf.secConf = brokerConf.getInitSecurityConfiguration();
                brokerConf.secConf.CApath = reference.secConf.CApath;
                brokerConf.secConf.clientCertificatePath = reference.secConf.clientCertificatePath;
                brokerConf.secConf.keyPath = reference.secConf.keyPath;
                brokerConf.secConf.CAPassword = reference.secConf.CAPassword;
                brokerConf.secConf.clientCertificatePassword = reference.secConf.clientCertificatePassword;
                brokerConf.secConf.keyPassword = reference.secConf.keyPassword;
            }else
                brokerConf.secConf = null;
            linksmartServiceCatalogOverwrite(brokerConf, brokerConf.alias);

            if(reference.equals(brokerConf))
                brokerConf.realProfile = reference.realProfile;

            return brokerConf;
        }catch (Exception e){
            throw new UnknownError(e.getMessage());
        }
    }
    static protected synchronized BrokerConfiguration linksmartServiceCatalogOverwrite(BrokerConfiguration brokerConfiguration, String alias){
        try {

            if (SCclient != null ){

                Service service =null;
                try{
                    service =SCclient.idGet(alias);
                }catch (RestClientException e){
                    if(_defaultSet)
                        return brokerConfiguration;
                    service = SCclient.idGet(conf.getString(Const.LINKSMART_BROKER));
                    _defaultSet = true;

                }

                URI url = new URI(service.getApis().get(SC_API_NAME));

                brokerConfiguration.hostname = url.getHost();
                if(protocols.contains(url.getScheme())) {
                    brokerConfiguration.port = url.getPort();
                    brokerConfiguration.hostname = url.getHost();
                }
                if(secureProtocols.contains(url.getScheme())){
                    brokerConfiguration.port = url.getPort();
                    brokerConfiguration.hostname = url.getHost();

                }

            }
        }catch (Exception ignored){
            //nothing
        }
        return  brokerConfiguration;
    }
    static private String getString(String key, String postFix, String Default){
        if(conf.containsKeyAnywhere(key + postFix))
            return conf.getString(key + postFix);
        if(conf.containsKeyAnywhere(key))
            return conf.getString(key);

        return Default;
    }
    static private boolean getBoolean(String key, String postFix, boolean Default){
        if(conf.containsKeyAnywhere(key + postFix))
            return conf.getBoolean(key + postFix);
        if(conf.containsKeyAnywhere(key))
            return conf.getBoolean(key);
        return Default;
    }
    static private int getInt(String key, String postFix, int Default){
        if(conf.containsKeyAnywhere(key + postFix))
            return conf.getInt(key + postFix);
        if(conf.containsKeyAnywhere(key))
            return conf.getInt(key);
        return Default;
    }
    static public MqttClient initClient(BrokerConfiguration brokerConf) throws MqttException {
        MqttClient mqttClient;
        if (brokerConf.filePersistence)
            mqttClient = new MqttClient(Broker.getBrokerURL(brokerConf.getHostname(),brokerConf.getPort()),brokerConf.getId(),new MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir")));
        else
            mqttClient = new MqttClient(Broker.getBrokerURL(brokerConf.getHostname(),brokerConf.getPort()),brokerConf.getId(),new MemoryPersistence());

        //mqttClient.connect(initMqttOptions(brokerConf));

        return mqttClient;
    }
    protected BrokerConfiguration(){
        loadConfiguration("",this);
    }
    public BrokerConfiguration(String alias){
        init(alias,id);
    }

    public BrokerConfiguration(String alias, String ID){
      init(alias,ID);
    }
    private void init(String alias, String id){
        this.id = id;
        if(alias!=null && loadConfigurations() != null)
            if(loadConfigurations().containsKey(alias))
                loadConfiguration(this, loadConfigurations().get(alias));
            else
                loadConfiguration(alias,this);
    }
    @JsonIgnore
    public MqttClient initClient() throws MqttException {
        return initClient(this);
    }
    public BrokerSecurityConfiguration getInitSecurityConfiguration(){

        if(secConf==null)
            secConf = new BrokerSecurityConfiguration();

        return secConf;

    }

    public String getId() {
        return id;
    }

    public String getAlias() {
        return alias;
    }

    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    public int getSecurePort() {
        return securePort;
    }

    public boolean isFilePersistence() {
        return filePersistence;
    }

    public int getSubQoS() {
        return subQoS;
    }

    public int getPubQoS() {
        return pubQoS;
    }

    public boolean isRetainPolicy() {
        return retainPolicy;
    }

    public BrokerSecurityConfiguration getSecConf() {
        return secConf;
    }

    public int getKeepAlive() {
        return keepAlive;
    }

    public int getTimeOut() {
        return timeOut;
    }

    public int getNoTries() {
        return noTries;
    }

    public int getReconnectWaitingTime() {
        return reconnectWaitingTime;
    }

    public String getURL(){
        if(secConf!=null)
            return Broker.getSecureBrokerURL(hostname,port);

        return Broker.getBrokerURL(hostname, port);
    }
    @JsonIgnore
    public MqttConnectOptions getInitMqttConnectOptions(){
        if(mqttOptions==null)
            mqttOptions = initMqttOptions(this);
        return mqttOptions;
    }
    @JsonIgnore
    public MqttConnectOptions getMqttConnectOptions(){
        if(mqttOptions==null)
            mqttOptions = initMqttOptions(this);
        return mqttOptions;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setSecurePort(int securePort) {
        this.securePort = securePort;
    }

    public void setFilePersistence(boolean filePersistence) {
        this.filePersistence = filePersistence;
    }

    public void setSubQoS(int subQoS) {
        this.subQoS = subQoS;
    }

    public void setPubQoS(int pubQoS) {
        this.pubQoS = pubQoS;
    }

    public void setRetainPolicy(boolean retainPolicy) {
        this.retainPolicy = retainPolicy;
    }

    public void setSecConf(BrokerSecurityConfiguration secConf) {
        this.secConf = secConf;
    }

    public void setKeepAlive(int keepAlive) {
        this.keepAlive = keepAlive;
    }

    public void setTimeOut(int timeOut) {
        this.timeOut = timeOut;
    }

    public void setNoTries(int noTries) {
        this.noTries = noTries;
    }

    public void setReconnectWaitingTime(int reconnectWaitingTime) {
        this.reconnectWaitingTime = reconnectWaitingTime;
    }
    @Override
    public boolean equals(Object o) {

        if (o == this)
            return true;
        if (o!=null && o instanceof BrokerConfiguration) {
            BrokerConfiguration aux = (BrokerConfiguration) o;
            boolean equal = aux.hostname.equals(hostname) && aux.securePort == securePort && aux.port == port && aux.filePersistence == filePersistence
                    && aux.subQoS == subQoS && aux.pubQoS == pubQoS && aux.retainPolicy == retainPolicy && aux.keepAlive == keepAlive && aux.timeOut == timeOut && aux.noTries == noTries
                    && aux.reconnectWaitingTime == reconnectWaitingTime;
            if (equal && secConf != null)
                return secConf.equals(aux.secConf);

            return equal;
        }
        return false;
    }
    @Override
    public String toString(){

        return "{" +
             //   "\"alias\":\""+alias+"\"," +
                "\"hostname\":\""+hostname+"\"," +
                "\"securePort\":\""+securePort+"\"," +
                "\"will\":\""+will +"\","+
                "\"willTopic\":\""+willTopic +"\","+
                "\"port\":\""+port+"\"," +
                "\"filePersistence\":\""+filePersistence+"\"," +
                "\"subQoS\":\""+subQoS+"\"," +
                "\"pubQoS\":\""+pubQoS+"\"," +
                "\"retainPolicy\":\""+retainPolicy+"\"," +
                "\"keepAlive\":\""+keepAlive+"\"," +
                "\"timeOut\":\""+timeOut+"\"," +
                "\"noTries\":\""+noTries+"\"," +
                "\"version\":\""+version.toString()+"\"," +
                "\"inFlightMessages\":\""+maxInFlightMessages+"\"," +
                "\"reconnectWaitingTime\":\""+reconnectWaitingTime +"\""+
                ( ( secConf != null ) ? (",\"brokerSecurityConfiguration\":"+secConf.toString() ): ("") )
                +"}";

    }
    @Override
    public int hashCode(){

        return toString().hashCode();
    }
    public int getMaxInFlightMessages() {
        return maxInFlightMessages;
    }

    public void setMaxInFlightMessages(int maxInFlightMessages) {
        this.maxInFlightMessages = maxInFlightMessages;
    }

    public MqttVersion getVersion() {
        return version;
    }

    public void setVersion(MqttVersion version) {
        this.version = version;
    }

    public boolean isAutomaticReconnect() {
        return automaticReconnect;
    }

    public void setAutomaticReconnect(boolean automaticReconnect) {
        this.automaticReconnect = automaticReconnect;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }

    public String getWill() {
        return will;
    }

    public void setWill(String will) {
        this.will = will;
    }

    public String getWillTopic() {
        return willTopic;
    }

    public void setWillTopic(String willTopic) {
        this.willTopic = willTopic;
    }
    @JsonIgnore
    public MqttConnectOptions getMqttOptions() {
        return mqttOptions;
    }
    @JsonIgnore
    public void setMqttOptions(MqttConnectOptions mqttOptions) {
        this.mqttOptions = mqttOptions;
    }

    public static BrokerConfiguration remove(String alias) {
        return aliasBrokerConf.remove(alias);
    }

    public class BrokerSecurityConfiguration{
        protected String CApath = "";

        protected String CAPassword = "";

        protected String clientCertificatePath = "";

        protected String clientCertificatePassword = "";

        protected String keyPath = "";

        protected String keyPassword = "";

        protected BrokerSecurityConfiguration(){
            // nothing
        }

        public String getCApath() {
            return CApath;
        }

        public String getCAPassword() {
            return CAPassword;
        }

        public String getClientCertificatePath() {
            return clientCertificatePath;
        }

        public String getClientCertificatePassword() {
            return clientCertificatePassword;
        }

        public String getKeyPath() {
            return keyPath;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setCApath(String CApath) {
            this.CApath = CApath;
        }

        public void setCAPassword(String CAPassword) {
            this.CAPassword = CAPassword;
        }

        public void setClientCertificatePath(String clientCertificatePath) {
            this.clientCertificatePath = clientCertificatePath;
        }

        public void setClientCertificatePassword(String clientCertificatePassword) {
            this.clientCertificatePassword = clientCertificatePassword;
        }

        public void setKeyPath(String keyPath) {
            this.keyPath = keyPath;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }
        @Override
        public boolean equals(Object o){

            if(o==this)
                return true;
            if(o!=null && o instanceof BrokerSecurityConfiguration ) {
                BrokerSecurityConfiguration aux = (BrokerSecurityConfiguration)o;
                return aux.CApath.equals(CApath)  && aux.CAPassword.equals(CAPassword) && aux.clientCertificatePath.equals(clientCertificatePath) && aux.clientCertificatePassword.equals(clientCertificatePassword) && aux.keyPath.equals(keyPath) && aux.keyPassword.equals(keyPassword);
            }
            return false;


        }
        @Override
        public String toString(){

            return "{" +
                    "\"CApath\":\""+CApath+"\"," +
                    "\"CAPassword\":\""+CAPassword+"\"," +
                    "\"clientCertificatePath\":\""+clientCertificatePath+"\"," +
                    "\"clientCertificatePassword\":\""+clientCertificatePassword+"\"," +
                    "\"keyPath\":\""+keyPath+"\"," +
                    "\"keyPassword\":\""+keyPassword+"\"" +
                    "}";

        }
        @Override
        public int hashCode(){

            return toString().hashCode();
        }

    }
    public enum  MqttVersion{
        DEFAULT, V3,V3_1,V3_1_1


    }

}
