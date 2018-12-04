package edu.monash.gitgraph.gitgraphserver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@WebServlet("/GitGraphServer")
public class GitGraphServer extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final String sConfigXmlFilePath = "/home/shin/Projects/gitRep/GitGraphServer/res/gitgraph_server_config.xml";
	private static final String sDatabaseUri = "bolt://localhost:7687";
	private static final String sDatabaseUser = "neo4j";
	private static final String sDatabasePwd = "123456";
	
	private volatile List<KZRestfulApi> mConfiguration; // stores config from xml
	private Driver driver; // neo4j sdk
    
    @Override
	public void init() {
    	loadConfiguration();
    	connectDatabase();
    }

    @Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String requestApiName = request.getPathInfo().substring(1); // delete the beginning slash
		System.out.println("doGet get request api: " + requestApiName);
		// get configurations and check availability
		List<KZRestfulApi> config = mConfiguration;
		if (config == null) {
			System.out.println("configuration file is unavailable!");
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			return;
		}
		// search requested api in configuration
		KZRestfulApi api = null;
		for (KZRestfulApi tmp : config) {
			if (tmp.name.equals(requestApiName))
				api = tmp;
		}
		if (api == null) {
			System.out.println("doGet, " + requestApiName + " , requested api doesn't exist!");
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		// add request params to configuration
		Map<String, Object> params = new HashMap<>();
		params.putAll(api.inputs);
		for (Enumeration<String> e = request.getParameterNames(); e.hasMoreElements();) {
			String requestParam = e.nextElement();
			params.put(requestParam, request.getParameter(requestParam));
		}
		// operate api.script to graph database and return api.output to response
		String result = runCypher(api.script, params);
		response.getWriter().append(result);
		response.setStatus(HttpServletResponse.SC_OK);
	}
	
	@Override
	public void destroy() {
		disconnectDatabase();
	}
	
	/**
	 * load config xml file from disk. will create the configuration.
	 * @throws ParserConfigurationException 
	 * @throws IOException 
	 * @throws SAXException 
	 */
	private List<KZRestfulApi> parseXmlConfigFile(String xmlFilePath) throws ParserConfigurationException, SAXException, IOException {
		File inputFile = new File(xmlFilePath);
		// build DOM tree from file
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(inputFile);
        doc.getDocumentElement().normalize();
        // create KZRestfulApi list based on api nodes
        List<KZRestfulApi> kzApiList = new ArrayList<KZRestfulApi>();
        // parse nodes from DOM
        NodeList nodeApiList = doc.getElementsByTagName("api");
        for (int i = 0; i < nodeApiList.getLength(); i++) {
        	Element nodeApi = (Element) nodeApiList.item(i); // api node
        	KZRestfulApi kzApi = new KZRestfulApi();
        	// name node of this api
        	Node nodeName = nodeApi.getElementsByTagName("name").item(0);
        	if (nodeName != null)
        		kzApi.name = nodeName.getTextContent().trim();
        	// output node of this api
        	Node nodeOutput = nodeApi.getElementsByTagName("output").item(0);
        	if (nodeOutput != null)
        		kzApi.output = nodeOutput.getTextContent().trim();
        	// script node of this api
        	Node nodeScript = nodeApi.getElementsByTagName("script").item(0);
        	if (nodeScript != null)
        		kzApi.script = nodeScript.getTextContent().trim();
        	// inputs node of this api
        	kzApi.inputs = new HashMap<>();
        	NodeList nodeInputsList = nodeApi.getElementsByTagName("input");
        	for (int j = 0; j < nodeInputsList.getLength(); j++) {
				Element nodeInput = (Element) nodeInputsList.item(j);
				kzApi.inputs.put(nodeInput.getAttribute("key"), nodeInput.getTextContent().trim());
			}
        	// add kzApi to api list
        	kzApiList.add(kzApi);
        }
        return kzApiList;
	}
	
	/**
	 * load or reload configuration xml file from disk. It's safe to reload by call this function directly.
	 */
	private void loadConfiguration() {
		try {
			// no need to consider concurrent problem when update configuration xml file.
			// if the last mConfiguration instance was being read when we change its reference to
			// a new instance, the last operation will continue to use the old instance.
			mConfiguration = parseXmlConfigFile(sConfigXmlFilePath);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private class KZRestfulApi {
		String name; // name of the restful API
		Map<String, Object> inputs; // params of the API from client
		String output; // returned output to client
		String script; // cypher script to operate graph database
	}
	
	private void connectDatabase() {
		driver = GraphDatabase.driver(sDatabaseUri, AuthTokens.basic(sDatabaseUser, sDatabasePwd));
	}
	
	private String runCypher(String script, Map<String, Object> params) {
		String ret = "";
		try (Session session = driver.session()) { // to let java invoke session.close() automatically
			StatementResult result = session.run(script, params);
			// TODO: change output format
			for (Record r : result.list())
				ret += r.toString();
		}
		return ret;
	}
	
	private void disconnectDatabase() {
		driver.close();
	}
}
