package i5.las2peer.webConnector;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;

import i5.httpServer.HttpRequest;
import i5.httpServer.HttpResponse;
import i5.httpServer.RequestHandler;
import i5.las2peer.execution.NoSuchServiceException;
import i5.las2peer.execution.NoSuchServiceMethodException;
import i5.las2peer.execution.ServiceInvocationException;

import i5.las2peer.webConnector.WebConnector;



import i5.las2peer.p2p.AgentNotKnownException;
import i5.las2peer.p2p.Node;
import i5.las2peer.p2p.TimeoutException;
import i5.las2peer.security.Agent;
import i5.las2peer.security.L2pSecurityException;
import i5.las2peer.security.Mediator;
import i5.las2peer.security.PassphraseAgent;

//import rice.p2p.util.Base64;


/**
 * A HttpServer RequestHandler for handling requests to the LAS2peer Web connector.
 * Each request will be distributed to its corresponding session.
 *
 * Current Problem (LAS related, maybe out-dated..):
 * This class will be used by a library (the HttpServer), so it has to be provided
 * as an library as well. To gain access to the configuration parameters the way
 * back to the service will be needed, but this is not allowed by the las class loaders.
 *
 * @author Holger Jan&szlig;en
 */



public class WebConnectorRequestHandler implements RequestHandler {

	private static final String AUTHENTICATION_FIELD = "Authentication";	
	private static final String REST_DECODER = "restDecoder";
	private WebConnector connector;
	private Node l2pNode;
	private Hashtable<Long,Agent> activeUsers;
	
	/**
	 * Standard Constructor
	 *
	 */
	public WebConnectorRequestHandler () {		
		activeUsers= new Hashtable<>();
	}
	
	
	/**
	 * set the connector handling this request processor
	 * @param connector
	 */
	public void setConnector ( WebConnector connector ) {
		this.connector = connector;
		l2pNode = connector.getL2pNode();		
	}
	
	/**
	 * Logs in a las2peer user
	 * @param request
	 * @param response
	 * @return -1 if no succesfull login else userId
	 * @throws UnsupportedEncodingException
	 */
	private long authenticate (HttpRequest request, HttpResponse response) throws UnsupportedEncodingException
	{
		
		final int BASIC_PREFIX_LENGTH="BASIC ".length();
		String userPass="";
		String username="";
		String password="";
		
		//Check for authentication information in header
		if(request.hasHeaderField(AUTHENTICATION_FIELD)
			&&(request.getHeaderField(AUTHENTICATION_FIELD).length()>BASIC_PREFIX_LENGTH))
		{
			//looks like: Authentication Basic <Byte64(name:pass)>
			userPass=request.getHeaderField(AUTHENTICATION_FIELD).substring(BASIC_PREFIX_LENGTH);
			//userPass=new String(Base64.decode(userPass), "UTF-8");
			int separatorPos=userPass.indexOf(':');
			
			//get username and password
			username=userPass.substring(0,separatorPos);
			password=userPass.substring(separatorPos+1);
			
			
			try
			{
				
				long userId;
				Agent userAgent;
				
				if ( username.matches ("-?[0-9].*") ) {//username is id?
					try {
						userId = Long.valueOf(username);
					} catch ( NumberFormatException e ) {
						throw new L2pSecurityException ("The given user does not contain a valid agent id!");
					}
				} else {//username is string
					userId = l2pNode.getAgentIdForLogin(username);
				}
				
				userAgent = l2pNode.getAgent(userId);
				
				if ( ! (userAgent instanceof PassphraseAgent ))
					throw new L2pSecurityException ("Agent is not passphrase protected!");
				
				((PassphraseAgent)userAgent).unlockPrivateKey(password);
				connector.logMessage("Login: "+username);
				
				if(!activeUsers.containsKey(userId)){
					activeUsers.put(userId, userAgent);
				}
				
				return userId;
				
			}catch (AgentNotKnownException e) {
				sendUnauthorizedResponse(response, null, request.getRemoteAddress() + ": login denied for user " + username);
			} catch (L2pSecurityException e) {
				sendUnauthorizedResponse( response, null, request.getRemoteAddress() + ": unauth access - prob. login problems");
			} catch (Exception e) {
				
				sendInternalErrorResponse(
						response, 
						"The server was unable to process your request because of an internal exception!", 
						"Exception in processing create session request: " + e);
			}
			
		}
		else
		{
			response.setStatus ( HttpResponse.STATUS_BAD_REQUEST );
			response.setContentType( "text/plain" );
			response.print ( "No authentication provided!" );
			connector.logError( "No authentication provided!" );
		}
		return -1;
	}
	/**
	 * Delegates the request data to a service method, which then decides what to do with it (maps it internally)
	 * @param request
	 * @param response
	 * @return
	 */
	private boolean invoke(Long userId, HttpRequest request, HttpResponse response) {
		
		String[] requestSplit=request.getPath().split("/",3);
		// first: empty (string starts with '/')
		// second: service name
		// third: URI rest
		String serviceName="";
		String methodName="";
		String restURI="";
		String content="";
		
		try {
			
			serviceName=requestSplit[1];
			methodName=REST_DECODER; //special method in service
			
			if(requestSplit.length>=3)
			{
				int varsstart=requestSplit[2].indexOf('?');
				if(varsstart>0)
					restURI=requestSplit[2].substring(0,varsstart);
				else
					restURI=requestSplit[2];
			}
			
			//http body
			content=request.getContentString();
			
			if(content==null)
				content="";
			//http method
			int httpMethodInt=request.getMethod();
			String httpMethod="get";
			
			switch (httpMethodInt) 
			{
				case HttpRequest.METHOD_GET:
					httpMethod="get";
					break;
				case HttpRequest.METHOD_HEAD:
					httpMethod="head";
					break;
				case HttpRequest.METHOD_DELETE:
					httpMethod="delete";
					break;
				case HttpRequest.METHOD_POST:
					httpMethod="post";
					break;
				case HttpRequest.METHOD_PUT:
					httpMethod="put";
					break;
				default:
					break;
			}
			
			
			
			
			
			String[][] variables = {};//extract variables from request ?param=value&param2=value2
			
			ArrayList<String[]> variablesList=new ArrayList<String[]>();
			@SuppressWarnings("rawtypes")
			Enumeration en = request.getGetVarNames();		
			//String querystr="";
			while(en.hasMoreElements())
			{
				String param = (String) en.nextElement();
				String val= request.getGetVar(param);
				
				String[] pair={param,val};
				
				variablesList.add(pair);
				//querystr+=param+" = "+val+" ";
			}
			connector.logMessage(httpMethod+" "+request.getUrl());
			variables=variablesList.toArray(new String[variablesList.size()][2]);
			
			
			Serializable[] parameters={httpMethod,restURI,variables,content};
			
			Serializable result;	
			if(activeUsers.containsKey(userId))	{
				Mediator mediator = l2pNode.getOrRegisterLocalMediator(activeUsers.get(userId));
				result= mediator.invoke(serviceName,methodName, parameters, connector.preferLocalServices());// invoke service method
				sendInvocationSuccess ( result, response );				
			}
			return true;
			
		} catch ( NoSuchServiceException e ) {
			sendNoSuchService(request, response, serviceName);			
		} catch ( TimeoutException e ) {
			sendNoSuchService(request, response, serviceName);
		} catch ( NoSuchServiceMethodException e ) {
			sendNoSuchMethod(request, response);
		} catch ( L2pSecurityException e ) {
			sendSecurityProblems(request, response, e);					
		} catch ( ServiceInvocationException e ) {
			if ( e.getCause() == null )
				sendResultInterpretationProblems(request, response);
			else
				sendInvocationException(request, response, e);								
		} catch ( InterruptedException e ) {
			sendInvocationInterrupted(request, response);
		} catch (Exception e){
			connector.logError("Error occured:" + request.getPath()+" "+e.getMessage() );
		}
		return false;
	}
	
	/**
	 * Logs the user out	 
	 * @param userId
	 */
	private void logout(Long userId)
	{
		try {
			Agent userAgent =activeUsers.get(userId);			
			l2pNode.unregisterAgent(userAgent);
			((PassphraseAgent)userAgent).lockPrivateKey();//don't know if really necessary
			activeUsers.remove(userId);			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	/**
	 * Handles a request (login, invoke)
	 */
	@Override
	public void processRequest(HttpRequest request, HttpResponse response) throws Exception {
		response.setHeaderField( "Server-Name", "Las2peer 0.1" );
		response.setContentType( "text/xml" );
		
		
		Long userId;
		if((userId=authenticate(request,response))!= -1)
			if(invoke(userId,request,response))
				logout(userId);	 
	
		
		
		
	}
	
	/**
	 * send a notification, that the requested service does not exists
	 * @param request
	 * @param response
	 * @param sRequest
	 */
	private void sendNoSuchService(HttpRequest request, HttpResponse response,
			String service) {
		response.clearContent();
		response.setStatus( HttpResponse.STATUS_SERVICE_UNAVAILABLE );
		response.setContentType( "text/plain" );
		response.println ( "The service you requested is not known to this server!" );
		
		connector.logError ("Service not found: " +service);
	}
	
	/**
	 * send a notification, that the requested method does not exists at the requested service
	 * @param request
	 * @param response
	 * @param sid
	 */
	private void sendNoSuchMethod(HttpRequest request, HttpResponse response) {
		response.clearContent();
		response.setStatus( HttpResponse.STATUS_NOT_FOUND );
		response.setContentType( "text/plain" );
		response.println ( "The method you requested is not known to this service!" );
		connector.logError("Invocation request " + request.getPath() + " for unknown service method");
	}
	
	/**
	 * send a notification, that security problems occurred during the requested service method
	 * @param request
	 * @param response
	 * @param sid
	 * @param e
	 */
	private void sendSecurityProblems(HttpRequest request,
			HttpResponse response, L2pSecurityException e) {
		response.clearContent();
		response.setStatus( HttpResponse.STATUS_FORBIDDEN );
		response.setContentType( "text/plain" );
		response.println ( "You don't have access to the method you requested" );
		connector.logError("Security exception in invocation request " + request.getPath());
		
		if ( System.getProperty("http-connector.printSecException") != null
				&& System.getProperty( "http-connector.printSecException").equals ( "true" ) )
			e.printStackTrace();
	}
	
	/**
	 * send a notification, that the result of the service invocation is
	 * not transportable 
	 * 
	 * @param request
	 * @param response
	 * @param sid
	 */
	private void sendResultInterpretationProblems(HttpRequest request,
			HttpResponse response) {
		// result interpretation problems
		response.clearContent();
		response.setStatus( HttpResponse.STATUS_INTERNAL_SERVER_ERROR );
		response.setContentType( "text/xml" );
		response.println ("the result of the method call is not transferable!");
		connector.logError("Exception while processing RMI: " + request.getPath());
	}
	
	/**
	 * send a notification about an exception which occurred inside the requested service method
	 * 
	 * @param request
	 * @param response
	 * @param sid
	 * @param e
	 */
	private void sendInvocationException(HttpRequest request,
			HttpResponse response, ServiceInvocationException e) {
		// internal exception in service method
		response.clearContent();
		response.setStatus( HttpResponse.STATUS_INTERNAL_SERVER_ERROR );
		response.setContentType( "text/xml" );
		connector.logError("Exception while processing RMI: " + request.getPath());
		
		Object[] ret = new Object[4];
		ret[0] = "Exception during RMI invocation!";
		
		ret[1] = e.getCause().getCause().getClass().getCanonicalName();
		ret[2] = e.getCause().getCause().getMessage();
		ret[3] = e.getCause().getCause();
		String code = ret[0]+"\n"+ret[1]+"\n"+ret[2]+"\n"+ret[3];
		response.println ( code );
	}
	
	/**
	 * send a notification, that the processing of the invocation has been interrupted
	 * 
	 * @param request
	 * @param response
	 */
	private void sendInvocationInterrupted(HttpRequest request,
			HttpResponse response) {
		response.clearContent();
		response.setStatus (HttpResponse.STATUS_INTERNAL_SERVER_ERROR );
		response.setContentType ( "text/plain");
		response.println ( "The invoction has been interrupted!");
		connector.logError("Invocation has been interrupted!");
	}	
	
	/**
	 * 
	 * @param result
	 * @param response
	 * @throws CodingException 
	 */
	private void sendInvocationSuccess ( Serializable result, HttpResponse response  ) {
		if ( result != null ) {
			response.setContentType( "text/xml" );
			String resultCode =  (result.toString());
			response.println ( resultCode );
			
		} else {
			response.setStatus( HttpResponse.STATUS_NO_CONTENT );
		}
	}
	
	/**
	 * send a message about an unauthorized request
	 * @param response
	 * @param logMessage
	 */
	private void sendUnauthorizedResponse(HttpResponse response, String answerMessage,
			String logMessage) {
		response.clearContent();
		response.setContentType( "text/plain" );
		if ( answerMessage != null)
			response.println ( answerMessage );
		response.setStatus( HttpResponse.STATUS_UNAUTHORIZED );
		connector.logMessage ( logMessage  );
	}
	
	/**
	 * send a response that an internal error occurred
	 * 
	 * @param response
	 * @param answerMessage
	 * @param logMessage
	 */
	private void sendInternalErrorResponse(HttpResponse response,
			String answerMessage, String logMessage) {
		response.clearContent();
		response.setContentType( "text/plain" );
		response.setStatus( HttpResponse.STATUS_INTERNAL_SERVER_ERROR );
		response.println ( answerMessage );
		connector.logMessage ( logMessage );
	}
}



