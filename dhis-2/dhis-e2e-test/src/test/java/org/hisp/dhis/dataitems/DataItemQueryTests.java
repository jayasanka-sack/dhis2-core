package org.hisp.dhis.dataitems;

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

import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.isA;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hisp.dhis.ApiTest;
import org.hisp.dhis.actions.LoginActions;
import org.hisp.dhis.actions.dataitem.DataItemActions;
import org.hisp.dhis.dto.ApiResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test cases related to GET "dataItems" endpoint. The tests and assertions are
 * based on the file "setup/metadata.json" => "programIndicators",
 * "dataElements".
 * 
 * The test cases using default pagination will imply "paging=true", which is
 * the default when "paging" is omitted.
 *
 * @author maikel arabori
 */
public class DataItemQueryTests extends ApiTest
{
    private static final int FOUND = 302;

    private static final int NOT_FOUND = 404;

    private static final int CONFLICT = 409;

    private DataItemActions dataItemActions;

    @BeforeAll
    public void before()
    {
        dataItemActions = new DataItemActions();

        login();
    }

    @Test
    public void testGetAllDataItemsUsingDefaultPagination()
    {
        // When
        final ApiResponse response = dataItemActions.get();

        // Then
        response.validate().statusCode( is( FOUND ) );
        response.validate().body( "pager", isA( Object.class ) );
        response.validate().body( "nameableObjects", isA( Object.class ) );
        response.validate().body( "nameableObjects", is( not( empty() ) ) );
        assertThat( hasMultipleDataItemTypes( response ), is( true ) );
    }

    @Test
    public void testGetAllDataItemsWithoutPagination()
    {
        // Given
        final String noPagination = "?paging=false";

        // When
        final ApiResponse response = dataItemActions.get( noPagination );

        // Then
        response.validate().statusCode( is( FOUND ) );
        response.validate().body( "pager", is( nullValue() ) );
        response.validate().body( "nameableObjects", isA( Object.class ) );
        response.validate().body( "nameableObjects", is( not( empty() ) ) );
        assertThat( hasMultipleDataItemTypes( response ), is( true ) );
    }

    @Test
    public void testGetAllDataItemsUsingDefaultPaginationOrderedByCode()
    {
        // When
        final ApiResponse response = dataItemActions.get( "?order=code:asc" );

        // Then
        response.validate().statusCode( is( FOUND ) );
        response.validate().body( "pager", isA( Object.class ) );
        response.validate().body( "nameableObjects", isA( Object.class ) );
        response.validate().body( "nameableObjects[0].dimensionItemType", isA( String.class ) );
        response.validate().body( "nameableObjects[0].code", is( "AAAAAAA-1234" ) );
    }

    @Test
    public void testFilterByDimensionTypeUsingDefaultPagination()
    {
        // Given
        final String theDimensionType = "PROGRAM_INDICATOR";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]";

        // When
        final ApiResponse response = dataItemActions.get( format( theUrlParams, theDimensionType ) );

        // Then
        response.validate().statusCode( is( FOUND ) );
        response.validate().body( "pager", isA( Object.class ) );
        response.validate().body( "nameableObjects[0].dimensionItemType", is( theDimensionType ) );
    }

    @Test
    public void testFilterByProgramUsingDefaultPagination()
    {
        // Given
        final String theDimensionType = "PROGRAM_INDICATOR";
        final String theProgramId = "BJ42SUrAvHo";
        final String aValidFilteringAttribute = "program.id";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]&filter=" + aValidFilteringAttribute
            + ":eq:%s&order=code:asc";

        // When
        final ApiResponse response = dataItemActions.get( format( theUrlParams, theDimensionType, theProgramId ) );

        // Then
        response.validate().statusCode( is( FOUND ) );
        response.validate().body( "pager", isA( Object.class ) );
        response.validate().body( "nameableObjects[0].code", is( "AAAAAAA-1234" ) );
    }

    @Test
    public void testFilterUsingInvalidDimensionTypeUsingDefaultPagination()
    {
        // Given
        final String anyInvalidDimensionType = "INVALID_TYPE";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]";

        // When
        final ApiResponse response = dataItemActions.get( format( theUrlParams, anyInvalidDimensionType ) );

        // Then
        response.validate().statusCode( is( CONFLICT ) );
        response.validate().body( "pager", is( nullValue() ) );
        response.validate().body( "httpStatus", is( "Conflict" ) );
        response.validate().body( "httpStatusCode", is( CONFLICT ) );
        response.validate().body( "status", is( "ERROR" ) );
        response.validate().body( "errorCode", is( "E2016" ) );
        response.validate().body( "message", containsString(
            "Unable to parse element `INVALID_TYPE` on filter `dimensionItemType`. The values available are:" ) );
    }

    @Test
    public void testWhenDataIsNotFoundUsingDefaultPagination()
    {
        // Given
        final String theDimensionType = "PROGRAM_INDICATOR";
        final String aNonExistingProgram = "non-existing-id";
        final String aValidFilteringAttribute = "program.id";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]&filter=" + aValidFilteringAttribute + ":eq:%s";

        // When
        final ApiResponse response = dataItemActions
            .get( format( theUrlParams, theDimensionType, aNonExistingProgram ) );

        // Then
        response.validate().statusCode( is( NOT_FOUND ) );
        response.validate().body( "nameableObjects", is( empty() ) );
    }

    @Test
    public void testFilterByProgramUsingNonexistentAttributeAndDefaultPagination()
    {
        // Given
        final String theDimensionType = "PROGRAM_INDICATOR";
        final String theProgramId = "BJ42SUrAvHo";
        final String aNonExistingAttr = "nonExistingAttr";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]&filter=" + aNonExistingAttr
            + ":eq:%s&order=code:asc";

        // When
        final ApiResponse response = dataItemActions.get( format( theUrlParams, theDimensionType, theProgramId ) );

        // Then
        response.validate().statusCode( is( CONFLICT ) );
        response.validate().body( "pager", is( nullValue() ) );
        response.validate().body( "httpStatus", is( "Conflict" ) );
        response.validate().body( "httpStatusCode", is( CONFLICT ) );
        response.validate().body( "status", is( "ERROR" ) );
        response.validate().body( "errorCode", is( nullValue() ) );
        response.validate().body( "message", containsString( "Unknown path property" ) );
    }

    @Test
    public void testFilterUsingInvalidAttributeTypeAndDefaultPagination()
    {
        // Given
        final String theDimensionType = "PROGRAM_INDICATOR";
        final String theProgramId = "BJ42SUrAvHo";
        final String anInvalidType = "program";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]&filter=" + anInvalidType
            + ":eq:%s&order=code:asc";

        // When
        final ApiResponse response = dataItemActions.get( format( theUrlParams, theDimensionType, theProgramId ) );

        // Then
        response.validate().statusCode( is( CONFLICT ) );
        response.validate().body( "pager", is( nullValue() ) );
        response.validate().body( "httpStatus", is( "Conflict" ) );
        response.validate().body( "httpStatusCode", is( CONFLICT ) );
        response.validate().body( "status", is( "ERROR" ) );
        response.validate().body( "errorCode", is( nullValue() ) );
        response.validate().body( "message", containsString( "Unable to parse" ) );
    }

    @Test
    public void testWhenFilteringByNonExistingProgramWithoutPagination()
    {
        // Given
        final String theDimensionType = "PROGRAM_INDICATOR";
        final String aNonExistingProgram = "non-existing-id";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]&filter=program.id:eq:%s&paging=false";

        // When
        final ApiResponse response = dataItemActions
            .get( format( theUrlParams, theDimensionType, aNonExistingProgram ) );

        // Then
        response.validate().statusCode( is( NOT_FOUND ) );
        response.validate().body( "pager", is( nullValue() ) );
        response.validate().body( "nameableObjects", is( empty() ) );
    }

    @Test
    public void testWhenFilteringByNonExistingDataItemTypeUsingDefaultPagination()
    {
        // Given
        final String theDimensionType = "DATA_SET";
        final String theUrlParams = "?filter=dimensionItemType:in:[%s]";

        // When
        final ApiResponse response = dataItemActions.get( format( theUrlParams, theDimensionType ) );

        // Then
        response.validate().statusCode( is( NOT_FOUND ) );
        response.validate().body( "pager", isA( Object.class ) );
        response.validate().body( "nameableObjects", is( empty() ) );
    }

    private boolean hasMultipleDataItemTypes( final ApiResponse response )
    {
        final List<String> elements = response.extractList( "nameableObjects.dimensionItemType" );
        final Set<String> dataItemsType = new HashSet<>();

        for ( final String element : elements )
        {
            dataItemsType.add( element );
        }

        final boolean hasMultipleDataItemTypes = dataItemsType.size() > 1;

        return hasMultipleDataItemTypes;
    }

    private void login()
    {
        new LoginActions().loginAsSuperUser();
    }
}
