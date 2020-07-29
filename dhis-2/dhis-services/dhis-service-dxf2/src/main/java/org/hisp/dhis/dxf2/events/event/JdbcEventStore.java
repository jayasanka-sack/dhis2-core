package org.hisp.dhis.dxf2.events.event;

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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.hisp.dhis.common.IdentifiableObjectUtils.getIdentifiers;
import static org.hisp.dhis.commons.util.TextUtils.getCommaDelimitedString;
import static org.hisp.dhis.commons.util.TextUtils.getQuotedCommaDelimitedString;
import static org.hisp.dhis.commons.util.TextUtils.removeLastComma;
import static org.hisp.dhis.commons.util.TextUtils.splitToArray;
import static org.hisp.dhis.dxf2.events.event.AbstractEventService.STATIC_EVENT_COLUMNS;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_ATTRIBUTE_OPTION_COMBO_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_COMPLETED_BY_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_COMPLETED_DATE_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_CREATED_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_DELETED;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_DUE_DATE_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_ENROLLMENT_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_EXECUTION_DATE_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_GEOMETRY;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_LAST_UPDATED_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_ORG_UNIT_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_ORG_UNIT_NAME;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_PROGRAM_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_PROGRAM_STAGE_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_STATUS_ID;
import static org.hisp.dhis.dxf2.events.event.EventSearchParams.EVENT_STORED_BY_ID;
import static org.hisp.dhis.dxf2.events.event.EventUtils.eventDataValuesToJson;
import static org.hisp.dhis.util.DateUtils.getDateAfterAddition;
import static org.hisp.dhis.util.DateUtils.getLongGmtDateString;
import static org.hisp.dhis.util.DateUtils.getMediumDateString;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.hisp.dhis.common.BaseIdentifiableObject;
import org.hisp.dhis.common.IdScheme;
import org.hisp.dhis.common.IdSchemes;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryItem;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.collection.CachingMap;
import org.hisp.dhis.commons.util.SqlHelper;
import org.hisp.dhis.commons.util.SystemUtils;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dxf2.events.enrollment.EnrollmentStatus;
import org.hisp.dhis.dxf2.events.report.EventRow;
import org.hisp.dhis.dxf2.events.trackedentity.Attribute;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.eventdatavalue.EventDataValue;
import org.hisp.dhis.hibernate.jsonb.type.JsonEventDataValueSetBinaryType;
import org.hisp.dhis.jdbc.BatchPreparedStatementSetterWithKeyHolder;
import org.hisp.dhis.jdbc.JdbcUtils;
import org.hisp.dhis.jdbc.StatementBuilder;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramStageInstance;
import org.hisp.dhis.program.ProgramStatus;
import org.hisp.dhis.program.ProgramType;
import org.hisp.dhis.query.Order;
import org.hisp.dhis.security.acl.AccessStringHelper;
import org.hisp.dhis.trackedentitycomment.TrackedEntityComment;
import org.hisp.dhis.user.CurrentUserService;
import org.hisp.dhis.user.User;
import org.hisp.dhis.util.DateUtils;
import org.hisp.dhis.util.ObjectUtils;
import org.postgis.PGgeometry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

import lombok.extern.slf4j.Slf4j;

/**
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
@Slf4j
@Repository( "org.hisp.dhis.dxf2.events.event.EventStore" )
public class JdbcEventStore implements EventStore
{
    private static final String PSI_STATUS_EQ = " psi.status = '";

    private static final String PSI_LASTUPDATED_GT = " psi.lastupdated >= '";

    private static final String DOT_NAME = ".name)";

    private static final Map<String, String> QUERY_PARAM_COL_MAP = ImmutableMap.<String, String>builder()
        .put( "event", "psi_uid" ).put( "program", "p_uid" ).put( "programStage", "ps_uid" )
        .put( "enrollment", "pi_uid" ).put( "enrollmentStatus", "pi_status" ).put( "orgUnit", "ou_uid" )
        .put( "orgUnitName", "ou_name" ).put( "trackedEntityInstance", "tei_uid" )
        .put( "eventDate", "psi_executiondate" ).put( "followup", "pi_followup" ).put( "status", "psi_status" )
        .put( "dueDate", "psi_duedate" ).put( "storedBy", "psi_storedby" ).put( "created", "psi_created" )
        .put( "lastUpdated", "psi_lastupdated" ).put( "completedBy", "psi_completedby" )
        .put( "attributeOptionCombo", "psi_aoc" ).put( "completedDate", "psi_completeddate" )
        .put( "deleted", "psi_deleted" ).put( "assignedUser", "user_assigned_username" ).build();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    // Cannot use DefaultRenderService mapper. Does not work properly - DHIS2-6102
    private static final ObjectReader eventDataValueJsonReader = JsonEventDataValueSetBinaryType.MAPPER
        .readerFor( new TypeReference<Map<String, EventDataValue>>()
        {
        } );

    private final StatementBuilder statementBuilder;

    private final JdbcTemplate jdbcTemplate;

    private final CurrentUserService currentUserService;

    private final IdentifiableObjectManager manager;

    private final ObjectMapper jsonMapper;

    private final Environment env;

    private final Cache<String, String> teiUpdateCache = new Cache2kBuilder<String, String>() {}
            .name( "teiUpdateCache" + RandomStringUtils.randomAlphabetic(5) )
            .expireAfterWrite( 10, TimeUnit.SECONDS )
            .build();

    public JdbcEventStore( StatementBuilder statementBuilder, JdbcTemplate jdbcTemplate,
        @Qualifier( "dataValueJsonMapper" ) ObjectMapper jsonMapper, CurrentUserService currentUserService,
        IdentifiableObjectManager identifiableObjectManager, Environment env )
    {
        checkNotNull( statementBuilder );
        checkNotNull( jdbcTemplate );
        checkNotNull( currentUserService );
        checkNotNull( identifiableObjectManager );
        checkNotNull( jsonMapper );
        checkNotNull( env );

        this.statementBuilder = statementBuilder;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.manager = identifiableObjectManager;
        this.jsonMapper = jsonMapper;
        this.env = env;
        
    }

    // -------------------------------------------------------------------------
    // EventStore implementation
    // -------------------------------------------------------------------------

    @Override
    public List<Event> getEvents( EventSearchParams params, List<OrganisationUnit> organisationUnits,
        Map<String, Set<String>> psdesWithSkipSyncTrue )
    {
        User user = currentUserService.getCurrentUser();

        setAccessiblePrograms( user, params );

        Map<String, Event> eventUidToEventMap = new HashMap<>( params.getPageSizeWithDefault() );
        List<Event> events = new ArrayList<>();

        String sql = buildSql( params, organisationUnits, user );
        SqlRowSet rowSet = jdbcTemplate.queryForRowSet( sql );

        log.debug( "Event query SQL: " + sql );

        Set<String> notes = new HashSet<>();

        while ( rowSet.next() )
        {
            if ( rowSet.getString( "psi_uid" ) == null
                || (params.getCategoryOptionCombo() == null && !isSuper( user ) && !userHasAccess( rowSet )) )
            {
                continue;
            }

            String psiUid = rowSet.getString( "psi_uid" );

            Event event;

            if ( !eventUidToEventMap.containsKey( psiUid ) )
            {
                validateIdentifiersPresence( rowSet, params.getIdSchemes(), true );

                event = new Event();
                eventUidToEventMap.put( psiUid, event );

                if ( !params.isSkipEventId() )
                {
                    event.setUid( psiUid );
                    event.setEvent( psiUid );
                }

                event.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );
                event.setStatus( EventStatus.valueOf( rowSet.getString( "psi_status" ) ) );

                event.setProgram( rowSet.getString( "p_identifier" ) );
                event.setProgramStage( rowSet.getString( "ps_identifier" ) );
                event.setOrgUnit( rowSet.getString( "ou_identifier" ) );
                event.setDeleted( rowSet.getBoolean( "psi_deleted" ) );

                ProgramType programType = ProgramType.fromValue( rowSet.getString( "p_type" ) );

                if ( programType != ProgramType.WITHOUT_REGISTRATION )
                {
                    event.setEnrollment( rowSet.getString( "pi_uid" ) );
                    event.setEnrollmentStatus( EnrollmentStatus
                        .fromProgramStatus( ProgramStatus.valueOf( rowSet.getString( "pi_status" ) ) ) );
                    event.setFollowup( rowSet.getBoolean( "pi_followup" ) );
                }

                if ( params.getCategoryOptionCombo() == null && !isSuper( user ) )
                {
                    event.setOptionSize( rowSet.getInt( "option_size" ) );
                }

                event.setAttributeOptionCombo( rowSet.getString( "coc_identifier" ) );
                event.setAttributeCategoryOptions( rowSet.getString( "deco_uid" ) );
                event.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );

                event.setStoredBy( rowSet.getString( "psi_storedby" ) );
                event.setOrgUnitName( rowSet.getString( "ou_name" ) );
                event.setDueDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_duedate" ) ) );
                event.setEventDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_executiondate" ) ) );
                event.setCreated( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_created" ) ) );
                event.setLastUpdated( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_lastupdated" ) ) );

                event.setCompletedBy( rowSet.getString( "psi_completedby" ) );
                event.setCompletedDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_completeddate" ) ) );

                if ( rowSet.getObject( "psi_geometry" ) != null )
                {
                    try
                    {
                        Geometry geom = new WKTReader().read( rowSet.getString( "psi_geometry" ) );

                        event.setGeometry( geom );
                        event.setCoordinate( new Coordinate( geom.getCoordinate().x, geom.getCoordinate().y ) );
                    }
                    catch ( ParseException e )
                    {
                        log.error( "Unable to read geometry for event '" + event.getUid() + "': ", e );
                    }
                }

                if ( rowSet.getObject( "user_assigned" ) != null )
                {
                    event.setAssignedUser( rowSet.getString( "user_assigned" ) );
                    event.setAssignedUserUsername( rowSet.getString( "user_assigned_username" ) );
                }

                events.add( event );
            }
            else
            {
                event = eventUidToEventMap.get( psiUid );
                String attributeCategoryCombination = event.getAttributeCategoryOptions();
                String currentAttributeCategoryCombination = rowSet.getString( "deco_uid" );

                if ( !attributeCategoryCombination.contains( currentAttributeCategoryCombination ) )
                {
                    event.setAttributeCategoryOptions(
                        attributeCategoryCombination + ";" + currentAttributeCategoryCombination );
                }
            }

            if ( !org.springframework.util.StringUtils.isEmpty( rowSet.getString( "psi_eventdatavalues" ) ) )
            {
                Set<EventDataValue> eventDataValues = convertEventDataValueJsonIntoSet(
                    rowSet.getString( "psi_eventdatavalues" ) );

                for ( EventDataValue dv : eventDataValues )
                {
                    DataValue dataValue = convertEventDataValueIntoDtoDataValue( dv );

                    if ( params.isSynchronizationQuery() )
                    {
                        if ( psdesWithSkipSyncTrue.containsKey( rowSet.getString( "ps_uid" ) ) && psdesWithSkipSyncTrue
                            .get( rowSet.getString( "ps_uid" ) ).contains( dv.getDataElement() ) )
                        {
                            dataValue.setSkipSynchronization( true );
                        }
                        else
                        {
                            dataValue.setSkipSynchronization( false );
                        }
                    }

                    event.getDataValues().add( dataValue );
                }
            }

            if ( rowSet.getString( "psinote_value" ) != null && !notes.contains( rowSet.getString( "psinote_id" ) ) )
            {
                Note note = new Note();
                note.setNote( rowSet.getString( "psinote_uid" ) );
                note.setValue( rowSet.getString( "psinote_value" ) );
                note.setStoredDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psinote_storeddate" ) ) );
                note.setStoredBy( rowSet.getString( "psinote_storedby" ) );

                event.getNotes().add( note );
                notes.add( rowSet.getString( "psinote_id" ) );
            }
        }

        IdSchemes idSchemes = ObjectUtils.firstNonNull( params.getIdSchemes(), new IdSchemes() );
        IdScheme dataElementIdScheme = idSchemes.getDataElementIdScheme();

        if ( dataElementIdScheme != IdScheme.ID && dataElementIdScheme != IdScheme.UID )
        {
            CachingMap<String, String> dataElementUidToIdentifierCache = new CachingMap<>();

            List<Collection<DataValue>> dataValuesList = events.stream().map( Event::getDataValues )
                .collect( Collectors.toList() );
            populateCache( dataElementIdScheme, dataValuesList, dataElementUidToIdentifierCache );
            convertDataValuesIdentifiers( dataElementIdScheme, dataValuesList, dataElementUidToIdentifierCache );
        }

        if ( params.getCategoryOptionCombo() == null && !isSuper( user ) )
        {
            return events.stream().filter( ev -> ev.getAttributeCategoryOptions() != null
                && splitToArray( ev.getAttributeCategoryOptions(), TextUtils.SEMICOLON ).size() == ev.getOptionSize() )
                .collect( Collectors.toList() );
        }

        return events;
    }

    public void saveEvents( List<ProgramStageInstance> events )
    {
        try
        {
            saveAllComments( saveAllEvents( events ) );
        }
        catch ( Exception e )
        {
            log.error( "An error occurred saving a batch", e );
            throw e;
        }
    }

    @Override
    public void updateEvents( List<ProgramStageInstance> programStageInstances )
    {
        try
        {
            jdbcTemplate.batchUpdate( UPDATE_EVENT_SQL, programStageInstances, programStageInstances.size(),
                ( ps, programStageInstance ) -> {
                    try
                    {
                        bindEventParamsForUpdate( ps, programStageInstance );
                    }
                    catch ( JsonProcessingException | SQLException e)
                    {
                        log.warn( "PSI failed to update and will be ignored. PSI UID: " + programStageInstance.getUid(),
                            programStageInstance.getUid(), e );
                    }
                } );
        }
        catch ( DataAccessException e )
        {
            log.error( "Error updating events", e );
            throw e;
        }

        saveAllComments( programStageInstances );
    }

    @Override
    public List<Map<String, String>> getEventsGrid( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        String sql = buildGridSql( params, organisationUnits );

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet( sql );

        log.debug( "Event query SQL: " + sql );

        List<Map<String, String>> list = new ArrayList<>();

        while ( rowSet.next() )
        {
            final Map<String, String> map = new HashMap<>();

            for ( String col : STATIC_EVENT_COLUMNS )
            {
                map.put( col, rowSet.getString( col ) );
            }

            for ( QueryItem item : params.getDataElements() )
            {
                map.put( item.getItemId(), rowSet.getString( item.getItemId() ) );
            }

            list.add( map );
        }

        return list;
    }

    @Override
    public List<EventRow> getEventRows( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        User user = currentUserService.getCurrentUser();

        setAccessiblePrograms( user, params );

        List<EventRow> eventRows = new ArrayList<>();

        String sql = buildSql( params, organisationUnits, user );
        SqlRowSet rowSet = jdbcTemplate.queryForRowSet( sql );

        log.debug( "Event query SQL: " + sql );

        EventRow eventRow = new EventRow();

        eventRow.setEvent( "not_valid" );

        Set<String> notes = new HashSet<>();

        Map<String, List<DataValue>> processedDataValues = new HashMap<>();

        while ( rowSet.next() )
        {
            if ( rowSet.getString( "psi_uid" ) == null
                || ( params.getCategoryOptionCombo() == null && !isSuper( user ) && !userHasAccess( rowSet ) ) )
            {
                continue;
            }

            if ( eventRow.getUid() == null || !eventRow.getUid().equals( rowSet.getString( "psi_uid" ) ) )
            {
                validateIdentifiersPresence( rowSet, params.getIdSchemes(), false );

                eventRow = new EventRow();

                eventRow.setUid( rowSet.getString( "psi_uid" ) );

                eventRow.setEvent( rowSet.getString( "psi_uid" ) );
                eventRow.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );
                eventRow.setTrackedEntityInstanceOrgUnit( rowSet.getString( "tei_ou" ) );
                eventRow.setTrackedEntityInstanceOrgUnitName( rowSet.getString( "tei_ou_name" ) );
                eventRow.setTrackedEntityInstanceCreated( rowSet.getString( "tei_created" ) );
                eventRow.setTrackedEntityInstanceInactive( rowSet.getBoolean( "tei_inactive" ) );
                eventRow.setDeleted( rowSet.getBoolean( "psi_deleted" ) );

                eventRow.setProgram( rowSet.getString( "p_identifier" ) );
                eventRow.setProgramStage( rowSet.getString( "ps_identifier" ) );
                eventRow.setOrgUnit( rowSet.getString( "ou_identifier" ) );

                ProgramType programType = ProgramType.fromValue( rowSet.getString( "p_type" ) );

                if ( programType == ProgramType.WITHOUT_REGISTRATION )
                {
                    eventRow.setEnrollment( rowSet.getString( "pi_uid" ) );
                    eventRow.setFollowup( rowSet.getBoolean( "pi_followup" ) );
                }

                eventRow.setTrackedEntityInstance( rowSet.getString( "tei_uid" ) );
                eventRow.setOrgUnitName( rowSet.getString( "ou_name" ) );
                eventRow.setDueDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_duedate" ) ) );
                eventRow.setEventDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psi_executiondate" ) ) );

                eventRows.add( eventRow );
            }

            if ( rowSet.getString( "pav_value" ) != null && rowSet.getString( "ta_uid" ) != null )
            {
                String valueType = rowSet.getString( "ta_valuetype" );

                Attribute attribute = new Attribute();
                attribute.setCreated( DateUtils.getIso8601NoTz( rowSet.getDate( "pav_created" ) ) );
                attribute.setLastUpdated( DateUtils.getIso8601NoTz( rowSet.getDate( "pav_lastupdated" ) ) );
                attribute.setValue( rowSet.getString( "pav_value" ) );
                attribute.setDisplayName( rowSet.getString( "ta_name" ) );
                attribute.setValueType( valueType != null ? ValueType.valueOf( valueType.toUpperCase() ) : null );
                attribute.setAttribute( rowSet.getString( "ta_uid" ) );

                eventRow.getAttributes().add( attribute );
            }

            if ( !org.springframework.util.StringUtils.isEmpty( rowSet.getString( "psi_eventdatavalues" ) )
                && !processedDataValues.containsKey( rowSet.getString( "psi_uid" ) ) )
            {
                List<DataValue> dataValues = new ArrayList<>();
                Set<EventDataValue> eventDataValues = convertEventDataValueJsonIntoSet(
                    rowSet.getString( "psi_eventdatavalues" ) );

                for ( EventDataValue dv : eventDataValues )
                {
                    dataValues.add( convertEventDataValueIntoDtoDataValue( dv ) );
                }
                processedDataValues.put( rowSet.getString( "psi_uid" ), dataValues );
            }

            if ( rowSet.getString( "psinote_value" ) != null && !notes.contains( rowSet.getString( "psinote_id" ) ) )
            {
                Note note = new Note();
                note.setNote( rowSet.getString( "psinote_uid" ) );
                note.setValue( rowSet.getString( "psinote_value" ) );
                note.setStoredDate( DateUtils.getIso8601NoTz( rowSet.getDate( "psinote_storeddate" ) ) );
                note.setStoredBy( rowSet.getString( "psinote_storedby" ) );

                eventRow.getNotes().add( note );
                notes.add( rowSet.getString( "psinote_id" ) );
            }
        }
        eventRows.forEach( e -> e.setDataValues( processedDataValues.get( e.getUid() ) ) );

        IdSchemes idSchemes = ObjectUtils.firstNonNull( params.getIdSchemes(), new IdSchemes() );
        IdScheme dataElementIdScheme = idSchemes.getDataElementIdScheme();

        if ( dataElementIdScheme != IdScheme.ID && dataElementIdScheme != IdScheme.UID )
        {
            CachingMap<String, String> dataElementUidToIdentifierCache = new CachingMap<>();

            List<Collection<DataValue>> dataValuesList = eventRows.stream().map( EventRow::getDataValues )
                .collect( Collectors.toList() );
            populateCache( dataElementIdScheme, dataValuesList, dataElementUidToIdentifierCache );
            convertDataValuesIdentifiers( dataElementIdScheme, dataValuesList, dataElementUidToIdentifierCache );
        }

        return eventRows;
    }

    private String getIdSqlBasedOnIdScheme( IdScheme idScheme, String uidSql, String attributeSql, String codeSql )
    {
        if ( idScheme == IdScheme.ID || idScheme == IdScheme.UID )
        {
            return uidSql;
        }
        else if ( idScheme.isAttribute() )
        {
            return String.format( attributeSql, idScheme.getAttribute() );
        }
        else
        {
            return codeSql;
        }
    }

    private void validateIdentifiersPresence( SqlRowSet rowSet, IdSchemes idSchemes,
                                              boolean validateCategoryOptionCombo )
    {
        if ( StringUtils.isEmpty( rowSet.getString( "p_identifier" ) ) )
        {
            throw new IllegalStateException( String.format( "Program %s does not have a value assigned for idScheme %s",
                    rowSet.getString( "p_uid" ), idSchemes.getProgramIdScheme().name() ) );
        }

        if ( StringUtils.isEmpty( rowSet.getString( "ps_identifier" ) ) )
        {
            throw new IllegalStateException(
                    String.format( "ProgramStage %s does not have a value assigned for idScheme %s",
                            rowSet.getString( "ps_uid" ), idSchemes.getProgramStageIdScheme().name() ) );
        }

        if ( StringUtils.isEmpty( rowSet.getString( "ou_identifier" ) ) )
        {
            throw new IllegalStateException( String.format( "OrgUnit %s does not have a value assigned for idScheme %s",
                    rowSet.getString( "ou_uid" ), idSchemes.getOrgUnitIdScheme().name() ) );
        }

        if ( validateCategoryOptionCombo && StringUtils.isEmpty( rowSet.getString( "coc_identifier" ) ) )
        {
            throw new IllegalStateException(
                    String.format( "CategoryOptionCombo %s does not have a value assigned for idScheme %s",
                            rowSet.getString( "coc_uid" ), idSchemes.getCategoryOptionComboIdScheme().name() ) );
        }
    }

    private String getEventSelectIdentifiersByIdScheme( IdSchemes idSchemes )
    {
        StringBuilder sqlBuilder = new StringBuilder();

        sqlBuilder.append( getIdSqlBasedOnIdScheme( idSchemes.getOrgUnitIdScheme(),
            "ou.uid as ou_identifier, ",
            "ou.attributevalues #>> '{%s, value}' as ou_identifier, ",
            "ou.code as ou_identifier, " ) );

        sqlBuilder.append( getIdSqlBasedOnIdScheme( idSchemes.getProgramIdScheme(),
            "p.uid as p_identifier, ",
            "p.attributevalues #>> '{%s, value}' as p_identifier, ",
            "p.code as p_identifier, " ) );

        sqlBuilder.append( getIdSqlBasedOnIdScheme( idSchemes.getProgramStageIdScheme(),
            "ps.uid as ps_identifier, ",
            "ps.attributevalues #>> '{%s, value}' as ps_identifier, ",
            "ps.code as ps_identifier, " ) );

        sqlBuilder.append( getIdSqlBasedOnIdScheme( idSchemes.getCategoryOptionComboIdScheme(),
            "coc.uid as coc_identifier, ",
            "coc.attributevalues #>> '{%s, value}' as coc_identifier, ",
            "coc.code as coc_identifier, " ) );

        return sqlBuilder.toString();
    }

    @Override
    public int getEventCount( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        User user = currentUserService.getCurrentUser();
        setAccessiblePrograms( user, params );

        String sql;

        if ( params.hasFilters() )
        {
            sql = buildGridSql( params, organisationUnits );
        }
        else
        {
            sql = getEventSelectQuery( params, organisationUnits, user );
        }

        sql = sql.replaceFirst( "select .*? from", "select count(*) from" );

        sql = sql.replaceFirst( "order .*? (desc|asc)", "" );

        sql = sql.replaceFirst( "limit \\d+ offset \\d+", "" );

        log.debug( "Event query count SQL: " + sql );

        return jdbcTemplate.queryForObject( sql, Integer.class );
    }

    private DataValue convertEventDataValueIntoDtoDataValue( EventDataValue eventDataValue )
    {
        DataValue dataValue = new DataValue();
        dataValue.setCreated( DateUtils.getIso8601NoTz( eventDataValue.getCreated() ) );
        dataValue.setLastUpdated( DateUtils.getIso8601NoTz( eventDataValue.getLastUpdated() ) );
        dataValue.setDataElement( eventDataValue.getDataElement() );
        dataValue.setValue( eventDataValue.getValue() );
        dataValue.setProvidedElsewhere( eventDataValue.getProvidedElsewhere() );
        dataValue.setStoredBy( eventDataValue.getStoredBy() );

        return dataValue;
    }

    private String buildGridSql( EventSearchParams params, List<OrganisationUnit> organisationUnits )
    {
        SqlHelper hlp = new SqlHelper();

        // ---------------------------------------------------------------------
        // Select clause
        // ---------------------------------------------------------------------

        StringBuilder sqlBuilder = new StringBuilder().append( "select psi.uid as " + EVENT_ID + ", " + "psi.created as " + EVENT_CREATED_ID + ", "
            + "psi.lastupdated as " + EVENT_LAST_UPDATED_ID + ", " + "psi.storedby as " + EVENT_STORED_BY_ID + ", "
            + "psi.completedby as " + EVENT_COMPLETED_BY_ID + ", " + "psi.completeddate as " + EVENT_COMPLETED_DATE_ID + ", "
            + "psi.duedate as " + EVENT_DUE_DATE_ID + ", " + "psi.executiondate as " + EVENT_EXECUTION_DATE_ID + ", "
            + "ou.uid as " + EVENT_ORG_UNIT_ID + ", " + "ou.name as " + EVENT_ORG_UNIT_NAME + ", "
            + "psi.status as " + EVENT_STATUS_ID + ", "
            + "pi.uid as " + EVENT_ENROLLMENT_ID + ", "
            + "ps.uid as " + EVENT_PROGRAM_STAGE_ID + ", " + "p.uid as "
            + EVENT_PROGRAM_ID + ", " + "coc.uid as " + EVENT_ATTRIBUTE_OPTION_COMBO_ID + ", " + "psi.deleted as " + EVENT_DELETED + ", "
            + "psi.geometry as " + EVENT_GEOMETRY + ", " );

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            String queryCol = item.isNumeric() ? "CAST( " + dataValueValueSql + " AS NUMERIC ) " : dataValueValueSql;
            queryCol += " as " + col + ", ";

            sqlBuilder.append( queryCol );
        }

        String intermediateSql = sqlBuilder.toString();
        sqlBuilder = new StringBuilder().append( removeLastComma( intermediateSql ) ).append( " " );

        // ---------------------------------------------------------------------
        // From and where clause
        // ---------------------------------------------------------------------

        sqlBuilder.append( getFromWhereClause( params, hlp, organisationUnits ) );

        // ---------------------------------------------------------------------
        // Order clause
        // ---------------------------------------------------------------------

        sqlBuilder.append( getGridOrderQuery( params ) );

        // ---------------------------------------------------------------------
        // Paging clause
        // ---------------------------------------------------------------------

        sqlBuilder.append( getEventPagingQuery( params ) );

        return sqlBuilder.toString();
    }

    /**
     * Query is based on three sub queries on event, data value and comment, which
     * are joined using program stage instance id. The purpose of the separate
     * queries is to be able to page properly on events.
     */
    private String buildSql( EventSearchParams params, List<OrganisationUnit> organisationUnits, User user )
    {
        StringBuilder sqlBuilder = new StringBuilder().append( "select * from (" );

        sqlBuilder.append( getEventSelectQuery( params, organisationUnits, user ) );

        sqlBuilder.append( getOrderQuery( params ) );

        sqlBuilder.append( getEventPagingQuery( params ) );

        sqlBuilder.append( ") as event left join (" );

        if ( params.isIncludeAttributes() )
        {
            sqlBuilder.append( getAttributeValueQuery() );

            sqlBuilder.append( ") as att on event.tei_id=att.pav_id left join (" );
        }

        sqlBuilder.append( getCommentQuery() );

        sqlBuilder.append( ") as cm on event.psi_id=cm.psic_id " );

        sqlBuilder.append( getOrderQuery( params ) );

        return sqlBuilder.toString();
    }

    private String getEventSelectQuery( EventSearchParams params, List<OrganisationUnit> organisationUnits, User user )
    {
        List<Long> orgUnitIds = getIdentifiers( organisationUnits );

        SqlHelper hlp = new SqlHelper();

        StringBuilder sqlBuilder = new StringBuilder().append( "select "
            + getEventSelectIdentifiersByIdScheme( params.getIdSchemes() ) + " psi.uid as psi_uid, "
            + "ou.uid as ou_uid, p.uid as p_uid, ps.uid as ps_uid, coc.uid as coc_uid, "
            + "psi.programstageinstanceid as psi_id, psi.status as psi_status, psi.executiondate as psi_executiondate, "
            + "psi.eventdatavalues as psi_eventdatavalues, psi.duedate as psi_duedate, psi.completedby as psi_completedby, psi.storedby as psi_storedby, "
            + "psi.created as psi_created, psi.lastupdated as psi_lastupdated, psi.completeddate as psi_completeddate, psi.deleted as psi_deleted, "
            + "ST_AsText( psi.geometry ) as psi_geometry, au.uid as user_assigned, auc.username as user_assigned_username, "
            + "cocco.categoryoptionid AS cocco_categoryoptionid, deco.uid AS deco_uid, " );

        if ( ( params.getCategoryOptionCombo() == null || params.getCategoryOptionCombo().isDefault() )
            && !isSuper( user ) )
        {
            sqlBuilder.append(
                "deco.publicaccess AS deco_publicaccess, decoa.uga_access AS uga_access, decoa.ua_access AS ua_access, cocount.option_size AS option_size, " );
        }

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            String queryCol = item.isNumeric() ? " CAST( " + dataValueValueSql + " AS NUMERIC)"
                : "lower(" + dataValueValueSql + ")";
            queryCol += " as " + col + ", ";

            sqlBuilder.append( queryCol );
        }

        sqlBuilder.append( "pi.uid as pi_uid, pi.status as pi_status, pi.followup as pi_followup, "
            + "p.type as p_type, ps.uid as ps_uid, ou.name as ou_name, "
            + "tei.trackedentityinstanceid as tei_id, tei.uid as tei_uid, teiou.uid as tei_ou, teiou.name as tei_ou_name, tei.created as tei_created, tei.inactive as tei_inactive "
            + "from programstageinstance psi "
            + "inner join programinstance pi on pi.programinstanceid=psi.programinstanceid "
            + "inner join program p on p.programid=pi.programid "
            + "inner join programstage ps on ps.programstageid=psi.programstageid "
            + "inner join categoryoptioncombo coc on coc.categoryoptioncomboid=psi.attributeoptioncomboid "
            + "inner join categoryoptioncombos_categoryoptions cocco on psi.attributeoptioncomboid=cocco.categoryoptioncomboid "
            + "inner join dataelementcategoryoption deco on cocco.categoryoptionid=deco.categoryoptionid "
            + "left join trackedentityinstance tei on tei.trackedentityinstanceid=pi.trackedentityinstanceid "
            + "left join organisationunit ou on (psi.organisationunitid=ou.organisationunitid) "
            + "left join organisationunit teiou on (tei.organisationunitid=teiou.organisationunitid) "
            + "left join users auc on (psi.assigneduserid=auc.userid) "
            + "left join userinfo au on (auc.userid=au.userinfoid) " );

        Set<String> joinedColumns = new HashSet<>();

        String eventDataValuesWhereSql = "";

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String optCol = item.getItemId() + "opt";
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            if ( !joinedColumns.contains( col ) )
            {
                if ( item.hasOptionSet() && item.hasFilter() )
                {
                    sqlBuilder.append( "inner join optionvalue as " ).append( optCol ).append( " on lower(" )
                        .append( optCol ).append( ".code) = " ).append( "lower(" ).append( dataValueValueSql )
                        .append( ") and " ).append( optCol ).append( ".optionsetid = " )
                        .append( item.getOptionSet().getId() ).append( " " );
                }

                joinedColumns.add( col );
            }

            if ( item.hasFilter() )
            {
                for ( QueryFilter filter : item.getFilters() )
                {
                    final String encodedFilter = statementBuilder.encode( filter.getFilter(), false );

                    final String queryCol = item.isNumeric() ? " CAST( " + dataValueValueSql + " AS NUMERIC)"
                        : "lower( " + dataValueValueSql + " )";

                    if ( !item.hasOptionSet() )
                    {
                        if ( !eventDataValuesWhereSql.isEmpty() )
                        {
                            eventDataValuesWhereSql += " and ";
                        }

                        eventDataValuesWhereSql += " " + queryCol + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( StringUtils.isNumeric( encodedFilter ) ? encodedFilter
                                : filter.getSqlFilter( encodedFilter ) )
                            + " ";
                    }
                    else if ( QueryOperator.IN.getValue().equalsIgnoreCase( filter.getSqlOperator() ) )
                    {
                        sqlBuilder.append( "and " ).append( queryCol ).append( " " ).append( filter.getSqlOperator() )
                            .append( " " )
                            .append( StringUtils.lowerCase( StringUtils.isNumeric( encodedFilter ) ? encodedFilter
                                : filter.getSqlFilter( encodedFilter ) ) )
                            .append( " " );
                    }
                    else if ( QueryOperator.LIKE.getValue().equalsIgnoreCase( filter.getSqlOperator() ) )
                    {
                        sqlBuilder.append( "and lower(" ).append( optCol ).append( DOT_NAME ).append( " " )
                            .append( filter.getSqlOperator() ).append( " " )
                            .append( StringUtils.lowerCase( filter.getSqlFilter( encodedFilter ) ) ).append( " " );
                    }
                    else
                    {
                        sqlBuilder.append( "and lower(" ).append( optCol ).append( DOT_NAME ).append( " " )
                            .append( filter.getSqlOperator() ).append( " " )
                            .append( StringUtils.lowerCase( StringUtils.isNumeric( encodedFilter ) ? encodedFilter
                                : filter.getSqlFilter( encodedFilter ) ) )
                            .append( " " );
                    }
                }
            }
        }

        if ( (params.getCategoryOptionCombo() == null || params.getCategoryOptionCombo().isDefault())
            && !isSuper( user ) )
        {
            sqlBuilder.append( getCategoryOptionSharingForUser( user ) );
        }

        if ( !eventDataValuesWhereSql.isEmpty() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( eventDataValuesWhereSql ).append( " " );
        }

        if ( params.getTrackedEntityInstance() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " tei.trackedentityinstanceid=" )
                .append( params.getTrackedEntityInstance().getId() ).append( " " );
        }

        if ( params.getProgram() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " p.programid = " ).append( params.getProgram().getId() )
                .append( " " );
        }

        if ( params.getProgramStage() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " ps.programstageid = " )
                .append( params.getProgramStage().getId() ).append( " " );
        }

        if ( params.getProgramStatus() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " pi.status = '" ).append( params.getProgramStatus() )
                .append( "' " );
        }

        if ( params.getFollowUp() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " pi.followup is " )
                .append( params.getFollowUp() ? "true" : "false" ).append( " " );
        }

        sqlBuilder.append( addLastUpdatedFilters( params, hlp, true ) );

        // Comparing milliseconds instead of always creating new Date( 0 );
        if ( params.getSkipChangedBefore() != null && params.getSkipChangedBefore().getTime() > 0 )
        {
            String skipChangedBefore = DateUtils.getLongDateString( params.getSkipChangedBefore() );
            sqlBuilder.append( hlp.whereAnd() ).append( PSI_LASTUPDATED_GT ).append( skipChangedBefore ).append( "' " );
        }

        if ( params.getCategoryOptionCombo() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.attributeoptioncomboid = " )
                .append( params.getCategoryOptionCombo().getId() ).append( " " );
        }

        if ( orgUnitIds != null && !orgUnitIds.isEmpty() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.organisationunitid in (" )
                .append( getCommaDelimitedString( orgUnitIds ) ).append( ") " );
        }

        if ( params.getStartDate() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (psi.executiondate >= '" )
                .append( getMediumDateString( params.getStartDate() ) ).append( "' " )
                .append( "or (psi.executiondate is null and psi.duedate >= '" )
                .append( getMediumDateString( params.getStartDate() ) ).append( "')) " );
        }

        if ( params.getEndDate() != null )
        {
            Date dateAfterEndDate = getDateAfterAddition( params.getEndDate(), 1 );
            sqlBuilder.append( hlp.whereAnd() ).append( " (psi.executiondate < '" )
                .append( getMediumDateString( dateAfterEndDate ) ).append( "' " )
                .append( "or (psi.executiondate is null and psi.duedate < '" )
                .append( getMediumDateString( dateAfterEndDate ) ).append( "')) " );
        }

        if ( params.getProgramType() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " p.type = '" ).append( params.getProgramType() )
                .append( "' " );
        }

        if ( params.getEventStatus() != null )
        {
            if ( params.getEventStatus() == EventStatus.VISITED )
            {
                sqlBuilder.append( hlp.whereAnd() ).append( PSI_STATUS_EQ ).append( EventStatus.ACTIVE.name() )
                    .append( "' and psi.executiondate is not null " );
            }
            else if ( params.getEventStatus() == EventStatus.OVERDUE )
            {
                sqlBuilder.append( hlp.whereAnd() ).append( " date(now()) > date(psi.duedate) and psi.status = '" )
                    .append( EventStatus.SCHEDULE.name() ).append( "' " );
            }
            else
            {
                sqlBuilder.append( hlp.whereAnd() ).append( PSI_STATUS_EQ ).append( params.getEventStatus().name() )
                    .append( "' " );
            }
        }

        if ( params.getEvents() != null && !params.getEvents().isEmpty() && !params.hasFilters() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (psi.uid in (" )
                .append( getQuotedCommaDelimitedString( params.getEvents() ) ).append( ")) " );
        }

        if ( params.hasAssignedUsers() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (au.uid in (" )
                .append( getQuotedCommaDelimitedString( params.getAssignedUsers() ) ).append( ")) " );
        }

        if ( params.isIncludeOnlyUnassignedEvents() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (au.uid is null) " );
        }

        if ( params.isIncludeOnlyAssignedEvents() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (au.uid is not null) " );
        }

        if ( !params.isIncludeDeleted() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.deleted is false " );
        }

        if ( params.hasSecurityFilter() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (p.uid in (" )
                .append( getQuotedCommaDelimitedString( params.getAccessiblePrograms() ) ).append( ")) " );
            sqlBuilder.append( hlp.whereAnd() ).append( " (ps.uid in (" )
                .append( getQuotedCommaDelimitedString( params.getAccessibleProgramStages() ) ).append( ")) " );
        }

        if ( params.isSynchronizationQuery() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.lastupdated > psi.lastsynchronized " );
        }

        return sqlBuilder.toString();
    }

    /**
     * From, join and where clause. For dataElement params, restriction is set in
     * inner join. For query params, restriction is set in where clause.
     */
    private String getFromWhereClause( EventSearchParams params, SqlHelper hlp,
        List<OrganisationUnit> organisationUnits )
    {
        StringBuilder sqlBuilder = new StringBuilder().append( "from programstageinstance psi "
            + "inner join programinstance pi on pi.programinstanceid = psi.programinstanceid "
            + "inner join program p on p.programid = pi.programid "
            + "inner join programstage ps on ps.programstageid = psi.programstageid "
            + "inner join categoryoptioncombo coc on coc.categoryoptioncomboid = psi.attributeoptioncomboid "
            + "inner join organisationunit ou on psi.organisationunitid = ou.organisationunitid "
            + "left join users auc on (psi.assigneduserid=auc.userid) "
            + "left join userinfo au on (auc.userid=au.userinfoid) " );

        Set<String> joinedColumns = new HashSet<>();

        String eventDataValuesWhereSql = "";

        for ( QueryItem item : params.getDataElementsAndFilters() )
        {
            final String col = item.getItemId();
            final String optCol = item.getItemId() + "opt";
            final String dataValueValueSql = "psi.eventdatavalues #>> '{" + col + ", value}'";

            if ( !joinedColumns.contains( col ) )
            {
                if ( item.hasOptionSet() && item.hasFilter() )
                {
                    sqlBuilder.append( "inner join optionvalue as " ).append( optCol ).append( " on lower(" )
                        .append( optCol ).append( ".code) = " ).append( "lower(" ).append( dataValueValueSql )
                        .append( ") and " ).append( optCol ).append( ".optionsetid = " )
                        .append( item.getOptionSet().getId() ).append( " " );
                }

                joinedColumns.add( col );
            }

            if ( item.hasFilter() )
            {
                for ( QueryFilter filter : item.getFilters() )
                {
                    final String encodedFilter = statementBuilder.encode( filter.getFilter(), false );

                    final String queryCol = item.isNumeric() ? " CAST( " + dataValueValueSql + " AS NUMERIC)"
                        : "lower(" + dataValueValueSql + ")";

                    if ( !item.hasOptionSet() )
                    {
                        if ( !eventDataValuesWhereSql.isEmpty() )
                        {
                            eventDataValuesWhereSql += " and ";
                        }

                        eventDataValuesWhereSql += " " + queryCol + " " + filter.getSqlOperator() + " "
                            + StringUtils.lowerCase( filter.getSqlFilter( encodedFilter ) ) + " ";
                    }
                    else if ( QueryOperator.IN.getValue().equalsIgnoreCase( filter.getSqlOperator() ) )
                    {
                        sqlBuilder.append( "and " ).append(queryCol).append(" ").append(filter.getSqlOperator()).append(" ").append(StringUtils.lowerCase(filter.getSqlFilter(encodedFilter))).append(" ");
                    }
                    else
                    {
                        sqlBuilder.append( "and lower( " ).append(optCol).append(DOT_NAME).append(" ").append(filter.getSqlOperator()).append(" ").append(StringUtils.lowerCase(filter.getSqlFilter(encodedFilter))).append(" ");
                    }
                }
            }
        }

        if ( !eventDataValuesWhereSql.isEmpty() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( eventDataValuesWhereSql ).append( " " );
        }

        if ( organisationUnits != null && !organisationUnits.isEmpty() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.organisationunitid in (" )
                .append( getCommaDelimitedString( getIdentifiers( organisationUnits ) ) ).append( ") " );
        }

        if ( params.getProgramStage() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " ps.programstageid = " )
                .append( params.getProgramStage().getId() ).append( " " );
        }

        if ( params.getCategoryOptionCombo() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.attributeoptioncomboid = " )
                .append( params.getCategoryOptionCombo().getId() ).append( " " );
        }

        if ( params.getStartDate() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (psi.executiondate >= '" )
                .append( getMediumDateString( params.getStartDate() ) ).append( "' " )
                .append( "or (psi.executiondate is null and psi.duedate >= '" )
                .append( getMediumDateString( params.getStartDate() ) ).append( "')) " );
        }

        if ( params.getEndDate() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (psi.executiondate <= '" )
                .append( getMediumDateString( params.getEndDate() ) ).append( "' " )
                .append( "or (psi.executiondate is null and psi.duedate <= '" )
                .append( getMediumDateString( params.getEndDate() ) ).append( "')) " );
        }

        sqlBuilder.append( addLastUpdatedFilters( params, hlp, false ) );

        if ( params.isSynchronizationQuery() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.lastupdated > psi.lastsynchronized " );
        }

        // Comparing milliseconds instead of always creating new Date( 0 )

        if ( params.getSkipChangedBefore() != null && params.getSkipChangedBefore().getTime() > 0 )
        {
            String skipChangedBefore = DateUtils.getLongDateString( params.getSkipChangedBefore() );
            sqlBuilder.append( hlp.whereAnd() ).append( PSI_LASTUPDATED_GT ).append( skipChangedBefore ).append( "' " );
        }

        if ( params.getDueDateStart() != null )
        {
            sqlBuilder.append(hlp.whereAnd()).append(" psi.duedate is not null and psi.duedate >= '").append(DateUtils.getLongDateString(params.getDueDateStart())).append("' ");
        }

        if ( params.getDueDateEnd() != null )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.duedate is not null and psi.duedate <= '" )
                .append( DateUtils.getLongDateString( params.getDueDateEnd() ) ).append( "' " );
        }

        if ( !params.isIncludeDeleted() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " psi.deleted is false " );
        }

        if ( params.getEventStatus() != null )
        {
            if ( params.getEventStatus() == EventStatus.VISITED )
            {
                sqlBuilder.append( hlp.whereAnd() ).append( PSI_STATUS_EQ ).append( EventStatus.ACTIVE.name() )
                    .append( "' and psi.executiondate is not null " );
            }
            else if ( params.getEventStatus() == EventStatus.OVERDUE )
            {
                sqlBuilder.append( hlp.whereAnd() ).append( " date(now()) > date(psi.duedate) and psi.status = '" )
                    .append( EventStatus.SCHEDULE.name() ).append( "' " );
            }
            else
            {
                sqlBuilder.append( hlp.whereAnd() ).append( PSI_STATUS_EQ ).append( params.getEventStatus().name() )
                    .append( "' " );
            }
        }

        if ( params.getEvents() != null && !params.getEvents().isEmpty() && !params.hasFilters() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (psi.uid in (" )
                .append( getQuotedCommaDelimitedString( params.getEvents() ) ).append( ")) " );
        }

        if ( params.hasAssignedUsers() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (au.uid in (" )
                .append( getQuotedCommaDelimitedString( params.getAssignedUsers() ) ).append( ")) " );
        }

        if ( params.isIncludeOnlyUnassignedEvents() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (au.uid is null) " );
        }

        if ( params.isIncludeOnlyAssignedEvents() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( " (au.uid is not null) " );
        }

        return sqlBuilder.toString();
    }

    private String addLastUpdatedFilters( EventSearchParams params, SqlHelper hlp, boolean useDateAfterEndDate )
    {
        StringBuilder sqlBuilder = new StringBuilder();

        if ( params.hasLastUpdatedDuration() )
        {
            sqlBuilder.append( hlp.whereAnd() ).append( PSI_LASTUPDATED_GT )
                .append( getLongGmtDateString( DateUtils.nowMinusDuration( params.getLastUpdatedDuration() ) ) )
                .append( "' " );
        }
        else
        {
            if ( params.hasLastUpdatedStartDate() )
            {
                sqlBuilder.append( hlp.whereAnd() ).append( PSI_LASTUPDATED_GT )
                    .append( DateUtils.getLongDateString( params.getLastUpdatedStartDate() ) ).append( "' " );
            }

            if ( params.hasLastUpdatedEndDate() )
            {
                if ( useDateAfterEndDate )
                {
                    Date dateAfterEndDate = getDateAfterAddition( params.getLastUpdatedEndDate(), 1 );
                    sqlBuilder.append( hlp.whereAnd() ).append( " psi.lastupdated < '" )
                        .append( DateUtils.getLongDateString( dateAfterEndDate ) ).append( "' " );
                }
                else
                {
                    sqlBuilder.append( hlp.whereAnd() ).append( " psi.lastupdated <= '" )
                        .append( DateUtils.getLongDateString( params.getLastUpdatedEndDate() ) ).append( "' " );
                }
            }
        }

        return sqlBuilder.toString();
    }

    private String getCategoryOptionSharingForUser( User user )
    {
        List<Long> userGroupIds = getIdentifiers( user.getGroups() );

        StringBuilder sqlBuilder = new StringBuilder().append( " left join ( " );

        sqlBuilder.append( "select categoryoptioncomboid, count(categoryoptioncomboid) as option_size from categoryoptioncombos_categoryoptions group by categoryoptioncomboid) "
                + "as cocount on coc.categoryoptioncomboid = cocount.categoryoptioncomboid "
                + "left join ("
                + "select deco.categoryoptionid as deco_id, deco.uid as deco_uid, deco.publicaccess AS deco_publicaccess, "
                + "couga.usergroupaccessid as uga_id, coua.useraccessid as ua_id, uga.access as uga_access, uga.usergroupid AS usrgrp_id, "
                + "ua.access as ua_access, ua.userid as usr_id "
                + "from dataelementcategoryoption deco "
                + "left join dataelementcategoryoptionusergroupaccesses couga on deco.categoryoptionid = couga.categoryoptionid "
                + "left join dataelementcategoryoptionuseraccesses coua on deco.categoryoptionid = coua.categoryoptionid "
                + "left join usergroupaccess uga on couga.usergroupaccessid = uga.usergroupaccessid "
                + "left join useraccess ua on coua.useraccessid = ua.useraccessid "
                + "where ua.userid = ").append( user.getId() );

        if ( userGroupIds != null && !userGroupIds.isEmpty() )
        {
            sqlBuilder.append( " or uga.usergroupid in (" ).append( getCommaDelimitedString( userGroupIds ) )
                .append( ") " );
        }

        sqlBuilder.append( " ) as decoa on cocco.categoryoptionid = decoa.deco_id " );

        return sqlBuilder.toString();
    }

    private String getEventPagingQuery( EventSearchParams params )
    {
        StringBuilder sqlBuilder = new StringBuilder().append( " " );

        if ( params.isPaging() )
        {
            sqlBuilder.append( "limit " ).append( params.getPageSizeWithDefault() ).append( " offset " )
                .append( params.getOffset() ).append( " " );
        }

        return sqlBuilder.toString();
    }

    private String getCommentQuery()
    {
        return  "select psic.programstageinstanceid as psic_id, psinote.trackedentitycommentid as psinote_id, psinote.commenttext as psinote_value, "
            + "psinote.created as psinote_storeddate, psinote.creator as psinote_storedby, psinote.uid as psinote_uid "
            + "from programstageinstancecomments psic "
            + "inner join trackedentitycomment psinote on psic.trackedentitycommentid=psinote.trackedentitycommentid ";

    }

    private String getGridOrderQuery( EventSearchParams params )
    {

        if ( params.getGridOrders() != null && params.getDataElements() != null && !params.getDataElements().isEmpty()
            && STATIC_EVENT_COLUMNS != null && !STATIC_EVENT_COLUMNS.isEmpty() )
        {
            List<String> orderFields = new ArrayList<>();

            for ( String order : params.getGridOrders() )
            {
                String[] prop = order.split( ":" );

                if ( prop.length == 2 && (prop[1].equals( "desc" ) || prop[1].equals( "asc" )) )
                {
                    if ( STATIC_EVENT_COLUMNS.contains( prop[0] ) )
                    {
                        orderFields.add( prop[0] + " " + prop[1] );
                    }
                    else
                    {
                        Set<QueryItem> queryItems = params.getDataElements();

                        for ( QueryItem item : queryItems )
                        {
                            if ( prop[0].equals( item.getItemId() ) )
                            {
                                orderFields.add( prop[0] + " " + prop[1] );
                                break;
                            }
                        }
                    }
                }
            }

            if ( !orderFields.isEmpty() )
            {
                return "order by " + StringUtils.join( orderFields, ',' );
            }
        }

        return "order by lastUpdated desc ";
    }

    private String getOrderQuery( EventSearchParams params )
    {
        ArrayList<String> orderFields = new ArrayList<String>();

        if ( params.getGridOrders() != null )
        {
            for ( String order : params.getGridOrders() )
            {
                String[] prop = order.split( ":" );

                if ( prop.length == 2 && (prop[1].equals( "desc" ) || prop[1].equals( "asc" )) )
                {
                    Set<QueryItem> items = params.getDataElements();

                    for ( QueryItem item : items )
                    {
                        if ( prop[0].equals( item.getItemId() ) )
                        {
                            orderFields.add( prop[0] + " " + prop[1] );
                            break;
                        }
                    }
                }
            }
        }

        if ( params.getOrders() != null )
        {
            for ( Order order : params.getOrders() )
            {
                if ( QUERY_PARAM_COL_MAP.containsKey( order.getProperty().getName() ) )
                {
                    String orderText = QUERY_PARAM_COL_MAP.get( order.getProperty().getName() );
                    orderText += order.isAscending() ? " asc" : " desc";
                    orderFields.add( orderText );
                }
            }
        }

        if ( !orderFields.isEmpty() )
        {
            return "order by " + StringUtils.join( orderFields, ',' ) + " ";
        }
        else
        {
            return "order by psi_lastupdated desc ";
        }
    }

    private String getAttributeValueQuery()
    {
        return "select pav.trackedentityinstanceid as pav_id, pav.created as pav_created, pav.lastupdated as pav_lastupdated, "
            + "pav.value as pav_value, ta.uid as ta_uid, ta.name as ta_name, ta.valuetype as ta_valuetype "
            + "from trackedentityattributevalue pav "
            + "inner join trackedentityattribute ta on pav.trackedentityattributeid=ta.trackedentityattributeid ";
    }

    private boolean isSuper( User user )
    {
        return user == null || user.isSuper();
    }

    /**
     * Saves a list of {@see ProgramStageInstance} using JDBC batch update.
     *
     * Note that this method is using JdbcTemplate to execute the batch operation,
     * therefore it's able to participate in any Spring-initiated transaction
     *
     * @param batch the list of {@see ProgramStageInstance}
     * @return the list of created {@see ProgramStageInstance} with primary keys
     *         assigned
     *
     */
    private List<ProgramStageInstance> saveAllEvents( List<ProgramStageInstance> batch )
    {
        JdbcUtils.batchUpdateWithKeyHolder( jdbcTemplate, INSERT_EVENT_SQL,
                new BatchPreparedStatementSetterWithKeyHolder<ProgramStageInstance>( batch )
            {
                @Override
                protected void setValues( PreparedStatement ps, ProgramStageInstance event )
                {
                    try
                    {
                        bindEventParamsForInsert( ps, event );
                    }
                    catch ( JsonProcessingException | SQLException e )
                    {
                        log.warn( "PSI failed to persist and will be ignored. PSI UID: " + event.getUid(),
                            event.getUid(), e );
                    }
                }

                @Override
                protected void setPrimaryKey( Map<String, Object> primaryKey, ProgramStageInstance event )
                {
                    event.setId( (Long) primaryKey.get( "programstageinstanceid" ) );
                }

                } );

        /*
         * Extract the primary keys from the created objects
         */
        List<Long> eventIds = batch.stream().map( BaseIdentifiableObject::getId ).collect( Collectors.toList() );

        /*
         * Assign the generated event PKs to the batch.
         *
         * If the generate event PKs size doesn't match the batch size, one or more PSI
         * were not persisted. Run an additional query to fetch the persisted PSI and
         * return only the PSI from the batch which are persisted.
         *
         */
        if ( eventIds.size() != batch.size() )
        {
            /* a Map where [key] -> PSI UID , [value] -> PSI ID */
            Map<String, Long> persisted = jdbcTemplate
                    .queryForList(
                            "SELECT uid, programstageinstanceid from programstageinstance where programstageinstanceid in ( "
                                    + Joiner.on( ";" ).join( eventIds ) + ")" )
                    .stream().collect(
                            Collectors.toMap( s -> (String) s.get( "uid" ), s -> (Long) s.get( "programstageinstanceid" ) ) );

            // @formatter:off
            return batch.stream()
                    .filter( psi -> persisted.containsKey( psi.getUid() ) )
                    .peek( psi -> psi.setId( persisted.get( psi.getUid() ) ) )
                    .collect( Collectors.toList() );
            // @formatter:on
        }
        else
        {
            for ( int i = 0; i < eventIds.size(); i++ )
            {
                batch.get( i ).setId( eventIds.get( i ) );
            }
            return batch;
        }
    }

    /**
     * Save all the comments ({@see TrackedEntityComment} for the list of
     * {@see ProgramStageInstance}
     * 
     * @param batch a List of {@see ProgramStageInstance}
     */
    private void saveAllComments( List<ProgramStageInstance> batch )
    {
        try
        {
            for ( ProgramStageInstance psi : batch )
            {
                int sortOrder = 1;
                if ( psi.getId() > 0 )
                {
                    // if the PSI is already in the db, fetch the latest sort order for the
                    // notes, to avoid conflicts
                    sortOrder = jdbcTemplate.queryForObject(
                        "select coalesce(max(sort_order) + 1, 1) from programstageinstancecomments where programstageinstanceid = "
                            + psi.getId(),
                        Integer.class );
                }
                List<TrackedEntityComment> comments = psi.getComments();

                for ( TrackedEntityComment comment : comments )
                {
                    if ( !StringUtils.isEmpty( comment.getCommentText() ) )
                    {
                        Long commentId = saveComment( comment );
                        if ( commentId != null && commentId != 0 )
                        {
                            saveCommentToEvent( psi.getId(), commentId, sortOrder );
                            sortOrder++;
                        }
                    }
                }
            }
        }
        catch ( DataAccessException dae )
        {
            log.error( "An error occurred saving a Program Stage Instance comment", dae );
            throw dae;
        }
    }

    private Long saveComment( TrackedEntityComment comment )
    {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        try
        {
            jdbcTemplate.update( connection -> {
                PreparedStatement ps = connection.prepareStatement( INSERT_EVENT_NOTE_SQL, new String[]{"trackedentitycommentid"} );

                ps.setString( 1, comment.getUid() );
                ps.setString( 2, comment.getCommentText() );
                ps.setTimestamp( 3, toTimestamp( comment.getCreated() ) );
                ps.setString( 4, comment.getCreator() );
                ps.setTimestamp( 5, toTimestamp( comment.getLastUpdated() ) );

                return ps;
            }, keyHolder );
        }
        catch ( DataAccessException e )
        {
            log.error( "An error occurred saving a TrackedEntityComment", e );
            return null;
        }

        return (long) keyHolder.getKey();
    }

    private void saveCommentToEvent( Long programStageInstanceId, Long commentId, int sortOrder )
    {
        try
        {
            jdbcTemplate.update( connection -> {
                PreparedStatement ps = connection.prepareStatement( INSERT_EVENT_COMMENT_LINK );

                ps.setLong( 1, programStageInstanceId );
                ps.setInt( 2, sortOrder );
                ps.setLong( 3, commentId );

                return ps;
            } );
        }
        catch ( DataAccessException e )
        {
            log.error(
                "An error occurred saving a link between a TrackedEntityComment and a ProgramStageInstance with primary key: "
                    + programStageInstanceId,
                e );
            throw e;
        }
    }

    public void updateTrackedEntityInstances( List<String> teiUids, User user )
    {
        if ( teiUids.isEmpty() )
        {
            return;
        }
        try
        {
            List<String> updatableTeiUid = new ArrayList<>();
            for ( String uid : teiUids )
            {
                if ( !teiUpdateCache.containsKey( uid ) )
                {

                    updatableTeiUid.add( uid );
                    teiUpdateCache.put( uid, uid );
                }
            }
            

            if ( !updatableTeiUid.isEmpty() )
            {
                final String result = updatableTeiUid.stream()
                    .map( s -> "'" + s + "'" )
                    .collect( Collectors.joining( ", " ) );

                jdbcTemplate.execute( getUpdateTeiSql(), (PreparedStatementCallback<Boolean>) psc -> {
                    psc.setString( 1, result );
                    psc.setTimestamp( 2, toTimestamp( new Date() ) );
                    if ( user != null )
                    {
                        psc.setLong( 3, user.getId() );
                    }
                    else
                    {
                        psc.setNull( 3, Types.INTEGER );
                    }
                    psc.setString( 4, result );
                    return psc.execute();
                } );
            }
        }
        catch ( DataAccessException e )
        {
            log.error( "An error occurred updating one or more Tracked Entity Instances", e );
            throw e;
        }
    }

    /**
     * Awful hack required for the H2-based tests to pass. H2 does not support the
     * "SKIP LOCKED" clause, therefore we need to remove it from the SQL statement
     * when executing the H2 tests.
     *
     * @return a SQL String
     */
    private String getUpdateTeiSql()
    {
        return String.format( UPDATE_TEI_SQL, SystemUtils.isTestRun( env.getActiveProfiles() ) ? "" : "SKIP LOCKED" );
    }

    private void bindEventParamsForInsert( PreparedStatement ps, ProgramStageInstance event )
        throws SQLException,
        JsonProcessingException
    {
        // @formatter:off
        ps.setLong(         1, event.getProgramInstance().getId() );
        ps.setLong(         2, event.getProgramStage().getId() );
        ps.setTimestamp(    3, toTimestamp( event.getDueDate() ) );
        ps.setTimestamp(    4, toTimestamp( event.getExecutionDate() ) );
        ps.setLong(         5, event.getOrganisationUnit().getId() );
        ps.setString(       6, event.getStatus().toString() );
        ps.setTimestamp(    7, toTimestamp( event.getCompletedDate() ) );
        ps.setString(       8, event.getUid() );
        ps.setTimestamp(    9, toTimestamp( new Date() ) );
        ps.setTimestamp(    10, toTimestamp( new Date() ) );
        ps.setLong(         11, event.getAttributeOptionCombo().getId() );
        ps.setString(       12, event.getStoredBy() );
        ps.setString(       13, event.getCompletedBy() );
        ps.setBoolean(      14, false );
        ps.setString(       15, event.getCode() );
        ps.setTimestamp(    16, toTimestamp( event.getCreatedAtClient() ) );
        ps.setTimestamp(    17, toTimestamp( event.getLastUpdatedAtClient() ) );
        ps.setObject(       18, toGeometry( event.getGeometry() )  );
        if ( event.getAssignedUser() != null )
        {
            ps.setLong(     19, event.getAssignedUser().getId() );
        }
        else
        {
            ps.setObject(   19, null );
        }
        ps.setObject(       20, eventDataValuesToJson( event.getEventDataValues(), this.jsonMapper ) );
        // @formatter:on
    }

    private void bindEventParamsForUpdate( PreparedStatement ps, ProgramStageInstance programStageInstance  ) throws SQLException, JsonProcessingException {

        ps.setLong( 1, programStageInstance.getProgramInstance().getId() );
        ps.setLong( 2, programStageInstance.getProgramStage().getId() );
        ps.setTimestamp( 3, new Timestamp( programStageInstance.getDueDate().getTime() ) );
        ps.setTimestamp( 4, new Timestamp( programStageInstance.getExecutionDate().getTime() ) );
        ps.setLong( 5, programStageInstance.getOrganisationUnit().getId() );
        ps.setString( 6, programStageInstance.getStatus().toString() );
        ps.setTimestamp( 7, toTimestamp( programStageInstance.getCompletedDate() ) );
        ps.setTimestamp( 8, toTimestamp( new Date() ) );
        ps.setLong( 9, programStageInstance.getAttributeOptionCombo().getId() );
        ps.setString( 10, programStageInstance.getStoredBy() );
        ps.setString( 11, programStageInstance.getCompletedBy() );
        ps.setBoolean( 12, programStageInstance.isDeleted() );
        ps.setString( 13, programStageInstance.getCode() );
        ps.setTimestamp( 14, toTimestamp( programStageInstance.getCreatedAtClient() ) );
        ps.setTimestamp( 15, toTimestamp( programStageInstance.getLastUpdatedAtClient() ) );
        ps.setObject( 16, toGeometry( programStageInstance.getGeometry()  )  );

        if ( programStageInstance.getAssignedUser() != null )
        {
            ps.setLong( 17, programStageInstance.getAssignedUser().getId() );
        }
        else
        {
            ps.setObject( 17, null );
        }

        ps.setObject( 18, eventDataValuesToJson( programStageInstance.getEventDataValues(), this.jsonMapper ) );
        ps.setString( 19, programStageInstance.getUid() );

    }

    private boolean userHasAccess( SqlRowSet rowSet )
    {
        if ( rowSet.wasNull() )
        {
            return true;
        }

        if ( rowSet.getString( "uga_access" ) == null && rowSet.getString( "ua_access" ) == null
            && rowSet.getString( "deco_publicaccess" ) == null )
        {
            return false;
        }

        return AccessStringHelper.isEnabled( rowSet.getString( "deco_publicaccess" ),
            AccessStringHelper.Permission.DATA_READ );
    }

    private Set<EventDataValue> convertEventDataValueJsonIntoSet( String jsonString )
    {
        try
        {
            Map<String, EventDataValue> data = eventDataValueJsonReader.readValue( jsonString );
            return JsonEventDataValueSetBinaryType.convertEventDataValuesMapIntoSet( data );
        }
        catch ( IOException e )
        {
            log.error( "Parsing EventDataValues json string failed. String value: " + jsonString );
            throw new IllegalArgumentException( e );
        }
    }

    private void convertDataValuesIdentifiers( IdScheme idScheme, List<Collection<DataValue>> dataValuesList,
        CachingMap<String, String> dataElementUidToIdentifierCache )
    {
        for ( Collection<DataValue> dataValues : dataValuesList )
        {
            for ( DataValue dv : dataValues )
            {
                String deUid = dv.getDataElement();
                String deIdentifier = dataElementUidToIdentifierCache.get( deUid );

                if ( StringUtils.isEmpty( deIdentifier ) )
                {
                    throw new IllegalStateException(
                        "DataElement: " + deUid + " does not have a value assigned for idScheme " + idScheme.name() );
                }

                dv.setDataElement( deIdentifier );
            }
        }
    }

    private void populateCache( IdScheme idScheme, List<Collection<DataValue>> dataValuesList,
        CachingMap<String, String> dataElementUidToIdentifierCache )
    {
        Set<String> deUids = new HashSet<>();

        for ( Collection<DataValue> dataValues : dataValuesList )
        {
            for ( DataValue dv : dataValues )
            {
                deUids.add( dv.getDataElement() );
            }
        }

        if ( !idScheme.isAttribute() )
        {
            List<DataElement> dataElements = manager.get( DataElement.class, deUids );
            dataElements.forEach( de -> dataElementUidToIdentifierCache.put( de.getUid(), de.getCode() ) );
        }
        else
        {
            if ( !deUids.isEmpty() )
            {
                String dataElementsUidsSqlString = getQuotedCommaDelimitedString( deUids );

                String deSql = "select de.uid, de.attributevalues #>> '{" + idScheme.getAttribute()
                    + ", value}' as value from dataelement de where de.uid in (" + dataElementsUidsSqlString + ") "
                    + "and de.attributevalues ? '" + idScheme.getAttribute() + "'";

                SqlRowSet deRowSet = jdbcTemplate.queryForRowSet( deSql );

                while ( deRowSet.next() )
                {
                    dataElementUidToIdentifierCache.put( deRowSet.getString( "uid" ), deRowSet.getString( "value" ) );
                }
            }
        }
    }

    public void delete( final List<Event> events )
    {
        if ( isNotEmpty( events ) )
        {
            final List<String> psiUids = events.stream().map( Event::getEvent ).collect( toList() );
            final String uids = "'" + Joiner.on( "," ).join( psiUids ) + "'";

            jdbcTemplate.execute( "DELETE FROM programstageinstancecomments where programstageinstanceid in "
                + "(select programstageinstanceid from programstageinstance where uid in (" + uids + ") )" );

            // remove link between comment and psi

            jdbcTemplate.execute( "DELETE FROM trackedentitycomment t "
                + "    where t.trackedentitycommentid in (SELECT psic.trackedentitycommentid "
                + "                FROM programstageinstancecomments psic "
                + "                WHERE psic.programstageinstanceid in "
                + "                (select programstageinstanceid from programstageinstance where uid in (" + uids
                + ")))" );

            jdbcTemplate.execute( "DELETE FROM programstageinstance where uid in ( " + uids + ")" );

        }
    }

    private void setAccessiblePrograms( User user, EventSearchParams params )
    {
        if ( !isSuper( user ) )
        {
            params.setAccessiblePrograms(
                    manager.getDataReadAll( Program.class ).stream().map( Program::getUid ).collect( Collectors.toSet() ) );

            params.setAccessibleProgramStages( manager.getDataReadAll( ProgramStage.class ).stream()
                    .map( ProgramStage::getUid ).collect( Collectors.toSet() ) );
        }
    }

    private Timestamp toTimestamp( Date date )
    {
        return date != null ? new Timestamp( date.getTime() ) : null;
    }

    private PGgeometry toGeometry( Geometry geometry )
            throws SQLException
    {
        return geometry != null ? new PGgeometry( geometry.toText() ) : null;
    }

}
