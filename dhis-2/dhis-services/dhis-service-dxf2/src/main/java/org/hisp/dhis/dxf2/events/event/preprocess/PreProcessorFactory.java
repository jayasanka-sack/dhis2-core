package org.hisp.dhis.dxf2.events.event.preprocess;

/*
 * Copyright (c) 2004-2020, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.util.List;
import java.util.Map;

import org.hisp.dhis.dxf2.events.event.Event;
import org.hisp.dhis.dxf2.events.event.validation.WorkContext;
import org.hisp.dhis.importexport.ImportStrategy;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * @author Luciano Fiandesio
 */
@Component( "trackerEventsPreProcessorFactory" )
@Slf4j
public class PreProcessorFactory
{
    private final Map<ImportStrategy, List<Class<? extends PreProcessor>>> eventPreProcessorsMap;

    public PreProcessorFactory( Map<ImportStrategy, List<Class<? extends PreProcessor>>> eventPreProcessorsMap )
    {
        this.eventPreProcessorsMap = eventPreProcessorsMap;
    }

    public void preProcessEvents(WorkContext ctx, List<Event> events )
    {
        PreProcessorRunner preProcessorRunner = new PreProcessorRunner(
            eventPreProcessorsMap.get( ctx.getImportOptions().getImportStrategy() ) );
        for ( Event event : events )
        {
            preProcessorRunner.executePreProcessingChain( event, ctx );
        }
    }

    static class PreProcessorRunner
    {
        private List<Class<? extends PreProcessor>> preprocessors;

        public PreProcessorRunner( List<Class<? extends PreProcessor>> preprocessors )
        {
            this.preprocessors = preprocessors;
        }

        public void executePreProcessingChain( Event event, WorkContext ctx )
        {
            for ( Class<? extends PreProcessor> preprocessor : preprocessors )
            {
                try
                {
                    PreProcessor pre = preprocessor.newInstance();
                    pre.process( event, ctx );
                }
                catch ( InstantiationException | IllegalAccessException e )
                {
                    log.error( "An error occurred during Event import validation", e );
                }
            }
        }
    }
}