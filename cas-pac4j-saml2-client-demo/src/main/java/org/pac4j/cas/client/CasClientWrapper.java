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
package org.pac4j.cas.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jasig.cas.client.authentication.AttributePrincipalImpl;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.util.XmlUtils;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.TicketValidationException;
import org.opensaml.saml2.core.Attribute;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.impl.AttributeImpl;
import org.opensaml.saml2.core.impl.NameIDImpl;
import org.opensaml.xml.XMLObject;
import org.pac4j.cas.credentials.CasWrapperCredentials;
import org.pac4j.cas.profile.CasProfile;
import org.pac4j.cas.profile.CasProxyProfile;
import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.Protocol;
import org.pac4j.core.client.RedirectAction;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.exception.RequiresHttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.saml.SAMLCredential;
import org.springframework.security.saml.SAMLEntryPoint;
import org.springframework.security.saml.SAMLLogoutFilter;
import org.springframework.security.saml.SAMLLogoutProcessingFilter;
import org.springframework.security.saml.SAMLProcessingFilter;
import org.springframework.webflow.execution.RequestContext;
import org.w3c.dom.Element;


public class CasClientWrapper extends BaseClient<CasWrapperCredentials, CasProfile> {

    protected static final Logger logger = LoggerFactory.getLogger(CasClientWrapper.class);

    private org.springframework.security.cas.web.CasAuthenticationEntryPoint casEntryPointCas;
    
    private org.springframework.security.cas.web.CasAuthenticationFilter casFilterCas;
    
    private org.jasig.cas.client.session.SingleSignOutFilter singleLogoutFilterCas;
    
    private org.springframework.security.web.authentication.logout.LogoutFilter requestSingleLogoutFilterCas;
    
    private boolean directRedirection = false;
    
    
	public org.springframework.security.cas.web.CasAuthenticationEntryPoint getCasEntryPointCas() {
		return casEntryPointCas;
	}

	public void setCasEntryPointCas(
			org.springframework.security.cas.web.CasAuthenticationEntryPoint casEntryPointCas) {
		this.casEntryPointCas = casEntryPointCas;
	}

	public org.springframework.security.cas.web.CasAuthenticationFilter getCasFilterCas() {
		return casFilterCas;
	}

	public void setCasFilterCas(
			org.springframework.security.cas.web.CasAuthenticationFilter casFilterCas) {
		this.casFilterCas = casFilterCas;
	}

	public org.jasig.cas.client.session.SingleSignOutFilter getSingleLogoutFilterCas() {
		return singleLogoutFilterCas;
	}

	public void setSingleLogoutFilterCas(
			org.jasig.cas.client.session.SingleSignOutFilter singleLogoutFilterCas) {
		this.singleLogoutFilterCas = singleLogoutFilterCas;
	}

	public org.springframework.security.web.authentication.logout.LogoutFilter getRequestSingleLogoutFilterCas() {
		return requestSingleLogoutFilterCas;
	}

	public void setRequestSingleLogoutFilterCas(
			org.springframework.security.web.authentication.logout.LogoutFilter requestSingleLogoutFilterCas) {
		this.requestSingleLogoutFilterCas = requestSingleLogoutFilterCas;
	}

	@Override
    protected void internalInit() {
    }

    @Override
    protected CasProfile retrieveUserProfile(final CasWrapperCredentials credentials, final WebContext context) {

        CasProfile casProfile = new CasProfile();
        casProfile.addAttribute("externalAuthentication",credentials.getExternalAuthentication());
        casProfile.setId(credentials.getServiceTicket());
         
        return casProfile;
    }

	
    @Override
    protected BaseClient<CasWrapperCredentials, CasProfile> newClient() {
    	CasClientWrapper client = new CasClientWrapper();
         return client;
    }

    @Override
    protected boolean isDirectRedirection() {
    	//true to go directly to idp
    	return directRedirection;
    }

    public void setDirectRedirection(boolean directRedirection) {
		this.directRedirection = directRedirection;
	}

	@Override
    protected RedirectAction retrieveRedirectAction(final WebContext wc) {
	   
	   try {
	   
	   J2EContext jc = (J2EContext) wc;
	   HttpServletRequest request = jc.getRequest();
       HttpServletResponse response = jc.getResponse();
       
       AuthenticationException e = null;
       
       casEntryPointCas.commence(request, response, e);
	
       } catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (ServletException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
       
	   return RedirectAction.dummy("dummy");
        
    }
    
     
    public boolean browserLogoutRedirectToIdp(final WebContext wc,org.springframework.security.core.Authentication authentication) {
    	return true;
    }
    

    
     
    protected CasWrapperCredentials retrieveCredentials(final WebContext wc) throws RequiresHttpAction {
    	
      J2EContext jc = (J2EContext) wc;
   	  HttpServletRequest request = jc.getRequest();
      HttpServletResponse response = jc.getResponse();
    	
      org.springframework.security.core.Authentication authentication = null;

    try {
		
  		authentication = casFilterCas.attemptAuthentication(request, response);
		
	} catch (AuthenticationException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	} catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
      
      org.springframework.security.core.userdetails.User user = (org.springframework.security.core.userdetails.User) authentication.getPrincipal();
    
   
      CasWrapperCredentials casCredentials = 
    		  new CasWrapperCredentials(
    				  user.getUsername(),
    				  this.getClass().getSimpleName(),
    				  authentication
    				  );
      
      return casCredentials;

    }
    
   


    @Override
    public Protocol getProtocol() {
        return Protocol.CAS;
    }
    
    
    
    
    /** Parameter name that stores logout request */
    private static String logoutParameterName = "logoutRequest";
    
    
    
    public static boolean isLogoutRequest(final HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && !isMultipartRequest(request) &&
            CommonUtils.isNotBlank(CommonUtils.safeGetParameter(request, logoutParameterName));
    }

    private static boolean isMultipartRequest(final HttpServletRequest request) {
        return request.getContentType() != null && request.getContentType().toLowerCase().startsWith("multipart");
    }    
    
    public static String getST(final HttpServletRequest request) {
    	String logoutMessage = CommonUtils.safeGetParameter(request, logoutParameterName);
    	String token = XmlUtils.getTextForElement(logoutMessage, "SessionIndex");
    	return token;
    }    
    
    
	
    
    
    
    
    
    
    
    
    
    

}