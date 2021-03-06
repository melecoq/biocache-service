/**************************************************************************
 *  Copyright (C) 2013 Atlas of Living Australia
 *  All Rights Reserved.
 * 
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 * 
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.web;

import au.org.ala.biocache.Store;
import au.org.ala.biocache.service.AuthService;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * Provides administration web services for the biocache-service.
 *
 * All services should require an API key.
 *
 * @author "Natasha Carter <Natasha.Carter@csiro.au>"
 */
@Controller
public class AdminController extends AbstractSecureController {

    /** Logger initialisation */
    private final static Logger logger = Logger.getLogger(AdminController.class);
    @Inject
    protected AuthService authService;

    @Value("${ingest.process.threads:4}")
    protected Integer ingestProcessingThreads;
    private java.util.Set<String> ingestingDrs = new java.util.HashSet<String>();

    /**
     * Ingests the list of supplied data resource uids. When no dr param is provided all data resources with the
     * configured collectory are ingested.
     *
     * Prevents the exact same ingestion from being started multiple times. Only prevents the exact same list of drs being
     * started.
     *
     * @param request
     * @param response
     * @throws Exception
     */
    @RequestMapping(value="/admin/ingest", method = RequestMethod.GET)
    public void ingestResources(HttpServletRequest request,
                                @RequestParam(value = "apiKey", required = true) String apiKey,
                                HttpServletResponse response) throws Exception{
        //performs an asynchronous ingest.
        final String dataResources = request.getParameter("dr");
        final String ingest = dataResources == null ? "all" : dataResources;
        if(shouldPerformOperation(request, response)){
            logger.debug("Attempting to ingest " + ingest);
            if(ingestingDrs.contains(ingest)){
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,  "Already ingesting " + ingest + ". Unable able to start a new ingestion.");
            } else {
                ingestingDrs.add(ingest);
                Thread t = new Thread(){
                    public void run(){
                        String[] drs = dataResources != null ? dataResources.split(","):null;
                        Store.ingest(drs,ingestProcessingThreads);
                        //remove the url from ingesting
                        ingestingDrs.remove(ingest);
                    }
                };
                t.start();
            }
        }
    }

    /**
     * Optimises the SOLR index.  Use this API to optimise the index so that the biocache-service
     * can enter read only mode during this process.
     * @param request
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = "/admin/index/optimise", method = RequestMethod.POST)
	public void optimiseIndex(HttpServletRequest request,
                              @RequestParam(value = "apiKey", required = true) String apiKey,
	                          HttpServletResponse response) throws Exception {
        if(shouldPerformOperation(request, response)){
            String message = Store.optimiseIndex();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(message);
        }
    }

    /**
     * Modifies the biocache-store:
     * - reopen the index
     * - enter/exit readonly mode.
     * @param readOnly
     * @param reopenIndex
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/admin/modify*", method = RequestMethod.GET)
    public @ResponseBody List<String> modifyServer(HttpServletRequest request,
                                                   @RequestParam(value = "ro", required = false) Boolean readOnly,
                                                   @RequestParam(value = "apiKey", required = true) String apiKey,
            @RequestParam(value = "reopenIndex", required = false,defaultValue="false") Boolean reopenIndex,
                                                   HttpServletResponse response) throws Exception {

        List<String> actionsPerformed = new java.util.ArrayList<String>();
        if(shouldPerformOperation(request, response)) {

            if (readOnly != null) {
                Store.setReadOnly(readOnly);
                actionsPerformed.add("Set readonly = " + readOnly);
            }
            if (reopenIndex) {
                Store.reopenIndex();
                actionsPerformed.add("Reopened the index");
            }
        }
        return actionsPerformed;
    }

    /**
     * Reindexes the supplied dr based on modifications since the supplied date.
     * 
     * @param request
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = "/admin/index/reindex", method = RequestMethod.POST)
    public void reindex(HttpServletRequest request,
                        @RequestParam(value = "apiKey", required = true) String apiKey,
            HttpServletResponse response)throws Exception{
        if(shouldPerformOperation(request, response)){
            String dataResource = request.getParameter("dataResource");
            String startDate = request.getParameter("startDate");
            logger.info("Re-indexing data resource: " + dataResource + " starting at " + startDate);
            Store.reindex(dataResource, startDate);
            response.setStatus(HttpServletResponse.SC_OK);
        }
    }

    /**
     * Returns true when in service is in readonly mode.
     * @return
     */
    @RequestMapping(value="/admin/isReadOnly", method=RequestMethod.GET)
    public @ResponseBody boolean isReadOnly() {
        return Store.isReadOnly();
    }

    /**
     * Reloads caches of translation maps between user names, ids, and email addresses 
     * @return Returns the string "Done". Will perform the reload asynchronously if the auth.user.details.path property is set to a non-empty string
     */
    @RequestMapping(value="/admin/refreshAuth", method=RequestMethod.GET)
    public @ResponseBody String refreshAuth() {
        authService.reloadCaches();
        return "Done";
    }
}
