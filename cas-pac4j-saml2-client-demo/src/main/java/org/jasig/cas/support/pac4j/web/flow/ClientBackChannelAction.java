/*
  Copyright 2014 Galasso

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.jasig.cas.support.pac4j.web.flow;

import java.net.URI;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang.StringUtils;
import org.jasig.cas.CentralAuthenticationService;
import org.jasig.cas.authentication.Authentication;
import org.jasig.cas.authentication.principal.Principal;
import org.jasig.cas.client.authentication.AttributePrincipalImpl;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.util.XmlUtils;
import org.jasig.cas.logout.LogoutManager;
import org.jasig.cas.logout.LogoutRequest;
import org.jasig.cas.logout.LogoutRequestStatus;
import org.jasig.cas.services.RegisteredService;
import org.jasig.cas.services.ServicesManager;
import org.jasig.cas.ticket.Ticket;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.jasig.cas.ticket.registry.TicketRegistry;
import org.jasig.cas.web.support.CookieRetrievingCookieGenerator;
import org.jasig.cas.web.support.WebUtils;
import org.opensaml.saml2.core.Attribute;
import org.opensaml.saml2.core.impl.AttributeImpl;
import org.opensaml.xml.XMLObject;
import org.pac4j.cas.client.CasClient;
import org.pac4j.cas.client.CasClientWrapper;
import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.Clients;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.RequiresHttpAction;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.saml.client.Saml2Client;
import org.pac4j.saml.client.Saml2ClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.saml.SAMLCredential;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.webflow.action.AbstractAction;
import org.springframework.webflow.context.ExternalContext;
import org.springframework.webflow.context.ExternalContextHolder;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;
import org.w3c.dom.Element;
 


/*
 * intercept remote identity provider call to Back Channel and send  via Back Channel to registered webapps
 */
@SuppressWarnings({ "unchecked" })
public final class ClientBackChannelAction extends AbstractAction {

    /**
     * The logger.
     */
    private final Logger logger = LoggerFactory.getLogger(ClientBackChannelAction.class);
    
    
    
    @NotNull
    private final LogoutManager logoutManager;

    /** CookieGenerator for TGT Cookie. */
    @NotNull
    private final CookieRetrievingCookieGenerator ticketGrantingTicketCookieGenerator;

    /** CookieGenerator for Warn Cookie. */
    @NotNull
    private final CookieRetrievingCookieGenerator warnCookieGenerator;


    @NotNull
    private final TicketRegistry ticketRegistry;

    /** New Ticket Registry for storing and retrieving services tickets. Can point to the same one as the ticketRegistry variable. */
    @NotNull
    private final TicketRegistry serviceTicketRegistry;

    
    @NotNull
    private final ServicesManager servicesManager;

   /**
     * The clients used for authentication.
     */
    @NotNull
    private final Clients clients;

    /**
     * The service for CAS authentication.
     */
    @NotNull
    private final CentralAuthenticationService centralAuthenticationService;


    public ClientBackChannelAction(
    		final CentralAuthenticationService theCentralAuthenticationService,
            final Clients theClients,
            final CookieRetrievingCookieGenerator tgtCookieGenerator,
            final CookieRetrievingCookieGenerator warnCookieGenerator,
            final TicketRegistry ticketRegistry,
            final TicketRegistry serviceTicketRegistry,
            final ServicesManager servicesManager,
            final LogoutManager logoutManager
     		) {
        this.centralAuthenticationService = theCentralAuthenticationService;
        this.clients = theClients;
        this.ticketGrantingTicketCookieGenerator = tgtCookieGenerator;
        this.warnCookieGenerator = warnCookieGenerator;
        this.ticketRegistry = ticketRegistry;
        this.logoutManager = logoutManager;
        
        if (serviceTicketRegistry == null) {
            this.serviceTicketRegistry = ticketRegistry;
        } else {
            this.serviceTicketRegistry = serviceTicketRegistry;
        }
          this.servicesManager = servicesManager;
    }

    /**
     * logout POST from remote server   (server-->server)
     * CAS: post logout from server is mapped to /login?clientname= (here)
     * SAML: post logout from server is mapped to /logout?action=SingleLogout (logout webflow)
     */
    @Override
    protected Event doExecute(final RequestContext context) throws Exception {
    	
  		final HttpServletRequest request = WebUtils.getHttpServletRequest(context);
	    final HttpServletResponse response = WebUtils.getHttpServletResponse(context);
	    final WebContext webContext = new J2EContext(request, response);
	    
	    log(request);
	    
        
	    String clientName = request.getParameter("client_name");
	    if(clientName == null){clientName = (String) request.getAttribute("client_name");}
	    
	    Object client = null;
       
	    if (StringUtils.isNotBlank(clientName)) {
	    	
	    	// get pac4j client
            client = (BaseClient<Credentials, CommonProfile>) this.clients.findClient(clientName);
         
	    	if (client instanceof Saml2ClientWrapper){
            	//do nothing: works with the slo logout mapped saml service on logout?action=SingleLogout
            	return success();
            }
       	 
            if (client instanceof CasClientWrapper){
            	
            	CasClientWrapper clientWrapper = (CasClientWrapper) client;
            	         
            	//remote cas ClientBackChannelAction
            	if(CasClientWrapper.isLogoutRequest(request)){
            		
            		final String token = CasClientWrapper.getST(request);
            		
            		logger.debug("destroy TGT with an external ST: "+token);  
            		
            		if (CommonUtils.isNotBlank(token)) {
                        
            			Collection<Ticket> ticketCollection = this.ticketRegistry.getTickets();
            			logger.debug("CAS ticketCollection.size: "+ticketCollection.size());
            			
            			
            			 
            			    for (Ticket ticket : ticketCollection) {
            			    	
            			    	if( ticket instanceof TicketGrantingTicket){
            			    		
            			    		TicketGrantingTicket ticketGrantingTicket = (TicketGrantingTicket)ticket;
            			    	   	String tgtId = ticketGrantingTicket.getId();
            			    	   	logger.debug("check for ticket.id: "+tgtId );
            			    	
            			    	   	org.jasig.cas.authentication.Authentication authentication 
            			    	   	= ticketGrantingTicket.getAuthentication();
            			    	
            			    	   		if(authentication!=null){
            			    		
            			    	   			String clientNameStored = authentication.getAttributes().get("clientName").toString();
            			    	   			
            			    	   			if(clientNameStored.equals(clientName)){
            			    		
            			    	   				logger.debug("client confirmed: "+clientName );
            			    		
            			    		
            			    	   					//get external auth
            			    	   					org.springframework.security.core.Authentication externalAuth = null;
            			    	                      
            		                                externalAuth = (org.springframework.security.core.Authentication) ClientLogoutAction.getExtAuthentication(authentication);
            			          	
            		                                if(externalAuth!=null){
            		                	 
            		                                	String extCredentials = (String) externalAuth.getCredentials();
            		                               
            		                                		
            		                                		if(extCredentials.equals(token)){
            	                    		
            		                                			logger.debug("token confirmed for tgtId: "+tgtId);
            		                   
            		                                			    //should do some LT validation from remote server ?
            		                                				//destroy the TGT and all his ST  !!! NOT WORKING
            		                                				List<LogoutRequest> logoutRequests = 
            		                                						this.centralAuthenticationService.destroyTicketGrantingTicket(tgtId);
            		                                				
            		                                						//reply
            		                                						logger.debug("... stop flow and respond to remote server");
                  	                              		                    webContext.setResponseStatus(HttpConstants.OK);
                  	                              		                    webContext.writeResponseContent(token+" was authenticated with tgtId: "+tgtId);
                  	                              		                    response.flushBuffer();
                  	                              		                    final ExternalContext externalContext = ExternalContextHolder.getExternalContext();
                  	                              		                    externalContext.recordResponseComplete();
                  	                              		                    return new Event(this, "stop");
        	                          
            	                    	}
            	                        
            		                 }
            	  		    	}
            			    	}
          			    	}
            			    }
                    	
                    	
            			    //not authenticated token
   	              			webContext.setResponseStatus(HttpConstants.OK);
            				webContext.writeResponseContent(token+" was not authenticated");
            				response.flushBuffer();
            				final ExternalContext externalContext = ExternalContextHolder.getExternalContext();
                        	externalContext.recordResponseComplete();
   	                       	return new Event(this, "stop");
                    	
                    }
               
                  
            	}
            	
             }
	    }
	    
	    
	    return success();
    }
        	
    
    
    
    
    
    private void log(HttpServletRequest request){
    	
    	   logger.debug("=========================================================" );
    	   logger.debug("ClientBackChannelAction.doExecute: " );
           logger.debug("request.method: " +request.getMethod() );
           logger.debug("request.requestURI: " +request.getRequestURI() );
           logger.debug("request.queryString: " +request.getQueryString() );
           logger.debug("request. host port remoteaddress: " +request.getRemoteHost() +" " +request.getRemotePort()+" "+request.getRemoteAddr() );
           logger.debug("request. parameter:" );
      	   Enumeration enParams = request.getParameterNames(); 
           	while(enParams.hasMoreElements()){
           		String paramName = (String)enParams.nextElement();
           		logger.debug(paramName+": "+request.getParameter(paramName));
           	}
           	
           	logger.debug("request. attribute:" );
           	Enumeration enParams2 = request.getAttributeNames(); 
           	while(enParams2.hasMoreElements()){
           		String paramName2 = (String)enParams2.nextElement();
           		logger.debug(paramName2+": "+request.getAttribute(paramName2));
           	}
            logger.debug("=========================================================" );
    }
    
    
    
    
    /** The redirect to app event in webflow. */
    public static final String REDIRECT_APP_EVENT = "redirectApp";
    

   
    
    
    
    
    

    
    
   
   
}
