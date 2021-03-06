/*
 *BSD 2-Clause License
 *
 *Copyright (c) 2019, itinordic All rights reserved.
 *
 *Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 *conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 *IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 *FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 *CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 *DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 *IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 *THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **/
package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import java.io.IOException;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Charles Chigoriwa
 */
public abstract class AbstractDhisDataSenderInterceptor extends CustomInterceptorAdapter {

    protected static Logger logger = LoggerFactory.getLogger(AbstractDhisDataSenderInterceptor.class);

    //This method is called just before the actual implementing server method is invoked.
    @Override
    public final boolean incomingRequestPostProcessed(RequestDetails theRequestDetails, HttpServletRequest theRequest, HttpServletResponse theResponse) throws AuthenticationException {
        RestOperationTypeEnum restOperationType = theRequestDetails.getRestOperationType();
        String frismHint = theRequestDetails.getHeader(FRISM_HINT);
        if (frismHint != null && frismHint.equalsIgnoreCase(NO_DHIS_SAVE)) {
            return true;
        }

        if (restOperationType.equals(RestOperationTypeEnum.CREATE) || restOperationType.equals(RestOperationTypeEnum.UPDATE)) {
            if (checkIfAuthorizedByAdapter()) {
                String authorization = theRequestDetails.getHeader("Authorization");
                if (isAuthorizedByAdapter(authorization)) {
                    return true;
                } else {
                    throw new CustomAbortException(500, "Fhir Adapter and/or its Dhis are not running");
                }
            } else if (checkIfAdapterIsRunning()) {
                if (isAdapterRunning()) {
                    return true;
                } else {
                    throw new CustomAbortException(500, "Adapter is not running.");
                }
            }
        }
        return true;
    }

    @Override
    public final void incomingRequestPreHandled(RestOperationTypeEnum theOperation, ActionRequestDetails theProcessedRequest) {
        RequestDetails theRequestDetails = theProcessedRequest.getRequestDetails();
        String frismHint = theRequestDetails.getHeader(FRISM_HINT);
        if (frismHint != null && frismHint.equalsIgnoreCase(NO_DHIS_SAVE)) {
            return;
        }

        if (theOperation.equals(RestOperationTypeEnum.CREATE) || theOperation.equals(RestOperationTypeEnum.UPDATE)) {
            IBaseResource resource = theProcessedRequest.getResource();
            setDhisSavedExtension(resource, false);
        }

        if (theOperation.equals(RestOperationTypeEnum.UPDATE)) {
            if (storeResourceBeforeUpdate()) {
                String authorization = theRequestDetails.getHeader("Authorization");
                String resourceName = theRequestDetails.getResourceName();
                FhirContext fhirContext = theRequestDetails.getFhirContext();
                IBaseResource resource = theProcessedRequest.getResource();
                IGenericClient client = getFhirClient(fhirContext, authorization, Collections.singletonMap(FRISM_HINT, NO_DHIS_SAVE));
                Bundle response = client.search().byUrl(resourceName + "?_id=" + resource.getIdElement().getIdPart())
                        .returnBundle(Bundle.class).execute();
                if (!GeneralUtility.isEmpty(response.getEntry())) {
                    IBaseResource resourceBeforeUpdate = response.getEntry().get(0).getResource();
                    theRequestDetails.getUserData().put(RESOURCE_BEFORE_UPDATE, resourceBeforeUpdate);
                }
            }
        }
    }

    //This method is called after the server implementation method has been called, but before any attempt to stream the
    //response back to the client.
    @Override
    public final boolean outgoingResponse(RequestDetails theRequestDetails, ResponseDetails theResponseDetails, HttpServletRequest theServletRequest, HttpServletResponse theServletResponse)
            throws AuthenticationException {

        String frismHint = theRequestDetails.getHeader(FRISM_HINT);
        if (frismHint != null && frismHint.equalsIgnoreCase(NO_DHIS_SAVE)) {
            return true;
        }

        RestOperationTypeEnum restOperationType = theRequestDetails.getRestOperationType();
        if (restOperationType.equals(RestOperationTypeEnum.CREATE) || restOperationType.equals(RestOperationTypeEnum.UPDATE)) {
           return saveInDhisViaAdapter(theRequestDetails, theResponseDetails, theServletRequest, theServletResponse);
        }
        return true;

    }
    
    protected boolean saveInDhisViaAdapter(RequestDetails theRequestDetails, ResponseDetails theResponseDetails, HttpServletRequest theServletRequest, HttpServletResponse theServletResponse){
        IBaseResource resource = theResponseDetails.getResponseResource();
            if (resource != null) {
                AdapterResource adapterResource = createAdapterResource(theRequestDetails, theResponseDetails);
                String clientResourceId = adapterResource.getClientResourceId();
                if (!GeneralUtility.isEmpty(clientResourceId)) {
                    String authorization = theRequestDetails.getHeader(AUTHORIZATION_HEADER);
                    String clientId = adapterResource.getClientId();
                    String resourceId = adapterResource.getResourceId();
                    String resourceInString = adapterResource.getResourceInString();
                    String resourceType = adapterResource.getResourceType();
                    String url = "/remote-fhir-express/" + clientId + "/" + clientResourceId + "/" + resourceType + "/" + resourceId;
                    String baseUrl = adapterResource.getBaseUrl();
                    url = baseUrl + url;
                    boolean savedInDhis;
                    boolean normalProceeding = true;
                    try {
                        CustomHttpUtility.httpPost(url, resourceInString, authorization, Collections.singletonMap("Content-Type", "application/json"));
                        savedInDhis = true;
                    } catch (IOException | ApiException ex) {
                        savedInDhis = false;
                        logger.error("Error saving a resource in dhis", ex);
                        normalProceeding = handleAdapterError(adapterResource, theRequestDetails, theResponseDetails, theServletRequest, theServletResponse);
                    }

                    if (savedInDhis) {
                        saveAsDhisSaved(theRequestDetails, theResponseDetails);
                    }

                    return normalProceeding;

                }

            }
            return true;
    }

    protected abstract boolean handleAdapterError(AdapterResource adapterResource, RequestDetails theRequestDetails, ResponseDetails theResponseDetails, HttpServletRequest theServletRequest, HttpServletResponse theServletResponse);

    protected abstract boolean checkIfAuthorizedByAdapter();

    protected abstract boolean storeResourceBeforeUpdate();

    protected abstract boolean checkIfAdapterIsRunning();
}
