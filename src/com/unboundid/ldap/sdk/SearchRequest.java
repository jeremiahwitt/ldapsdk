/*
 * Copyright 2007-2009 UnboundID Corp.
 * All Rights Reserved.
 */
/*
 * Copyright (C) 2008-2009 UnboundID Corp.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */
package com.unboundid.ldap.sdk;



import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.unboundid.asn1.ASN1Boolean;
import com.unboundid.asn1.ASN1Buffer;
import com.unboundid.asn1.ASN1BufferSequence;
import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1Enumerated;
import com.unboundid.asn1.ASN1Integer;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.asn1.ASN1Sequence;
import com.unboundid.ldap.protocol.LDAPMessage;
import com.unboundid.ldap.protocol.LDAPResponse;
import com.unboundid.ldap.protocol.ProtocolOp;
import com.unboundid.util.InternalUseOnly;
import com.unboundid.util.Mutable;
import com.unboundid.util.ThreadSafety;
import com.unboundid.util.ThreadSafetyLevel;

import static com.unboundid.ldap.sdk.LDAPMessages.*;
import static com.unboundid.util.Debug.*;
import static com.unboundid.util.StaticUtils.*;
import static com.unboundid.util.Validator.*;



/**
 * This class implements the processing necessary to perform an LDAPv3 search
 * operation, which can be used to retrieve entries that match a given set of
 * criteria.  A search request may include the following elements:
 * <UL>
 *   <LI>Base DN -- Specifies the base DN for the search.  Only entries at or
 *       below this location in the server (based on the scope) will be
 *       considered potential matches.</LI>
 *   <LI>Scope -- Specifies the range of entries relative to the base DN that
 *       may be considered potential matches.</LI>
 *   <LI>Dereference Policy -- Specifies the behavior that the server should
 *       exhibit if any alias entries are encountered while processing the
 *       search.  If no dereference policy is provided, then a default of
 *       {@code DereferencePolicy.NEVER} will be used.</LI>
 *   <LI>Size Limit -- Specifies the maximum number of entries that should be
 *       returned from the search.  A value of zero indicates that there should
 *       not be any limit enforced.  Note that the directory server may also
 *       be configured with a server-side size limit which can also limit the
 *       number of entries that may be returned to the client and in that case
 *       the smaller of the client-side and server-side limits will be
 *       used.  If no size limit is provided, then a default of zero (unlimited)
 *       will be used.</LI>
 *   <LI>Time Limit -- Specifies the maximum length of time in seconds that the
 *       server should spend processing the search.  A value of zero indicates
 *       that there should not be any limit enforced.  Note that the directory
 *       server may also be configured with a server-side time limit which can
 *       also limit the processing time, and in that case the smaller of the
 *       client-side and server-side limits will be used.  If no time limit is
 *       provided, then a default of zero (unlimited) will be used.</LI>
 *   <LI>Types Only -- Indicates whether matching entries should include only
 *       attribute names, or both attribute names and values.  If no value is
 *       provided, then a default of {@code false} will be used.</LI>
 *   <LI>Filter -- Specifies the criteria for determining which entries should
 *       be returned.  See the {@link Filter} class for the types of filters
 *       that may be used.
 *       <BR><BR>
 *       Note that filters can be specified using either their string
 *       representations or as {@link Filter} objects.  As noted in the
 *       documentation for the {@link Filter} class, using the string
 *       representation may be somewhat dangerous if the data is not properly
 *       sanitized because special characters contained in the filter may cause
 *       it to be invalid or worse expose a vulnerability that could cause the
 *       filter to request more information than was intended.  As a result, if
 *       the filter may include special characters or user-provided strings,
 *       then it is recommended that you use {@link Filter} objects created from
 *       their individual components rather than their string representations.
 * </LI>
 *   <LI>Attributes -- Specifies the set of attributes that should be included
 *       in matching entries.  If no attributes are provided, then the server
 *       will default to returning all user attributes.  If a specified set of
 *       attributes is given, then only those attributes will be included.
 *       Values that may be included to indicate a special meaning include:
 *       <UL>
 *         <LI>{@code NO_ATTRIBUTES} -- Indicates that no attributes should be
 *             returned.  That is, only the DNs of matching entries will be
 *             returned.</LI>
 *         <LI>{@code ALL_USER_ATTRIBUTES} -- Indicates that all user attributes
 *             should be included in matching entries.  This is the default if
 *             no attributes are provided, but this special value may be
 *             included if a specific set of operational attributes should be
 *             included along with all user attributes.</LI>
 *         <LI>{@code ALL_OPERATIONAL_ATTRIBUTES} -- Indicates that all
 *             operational attributes should be included in matching
 *             entries.</LI>
 *       </UL>
 *       These special values may be used alone or in conjunction with each
 *       other and/or any specific attribute names or OIDs.</LI>
 *   <LI>An optional set of controls to include in the request to send to the
 *       server.</LI>
 *   <LI>An optional {@link SearchResultListener} which may be used to process
 *       search result entries and search result references returned by the
 *       server in the course of processing the request.  If this is
 *       {@code null}, then the entries and references will be collected and
 *       returned in the {@link SearchResult} object that is returned.</LI>
 * </UL>
 * When processing a search operation, there are three ways that the returned
 * entries and references may be accessed:
 * <UL>
 *   <LI>If the {@link LDAPInterface#search(SearchRequest)} method is used and
 *       the provided search request does not include a
 *       {@link SearchResultListener} object, then the entries and references
 *       will be collected internally and made available in the
 *       {@link SearchResult} object that is returned.</LI>
 *   <LI>If the {@link LDAPInterface#search(SearchRequest)} method is used and
 *       the provided search request does include a {@link SearchResultListener}
 *       object, then that listener will be used to provide access to the
 *       entries and references, and they will not be present in the
 *       {@link SearchResult} object (although the number of entries and
 *       references returned will still be available).</LI>
 *   <LI>The {@link LDAPEntrySource} object may be used to access the entries
 *        and references returned from the search.  It uses an
 *        {@code Iterator}-like API to provide access to the entries that are
 *        returned, and any references returned will be included in the
 *        {@link EntrySourceException} thrown on the appropriate call to
 *        {@link LDAPEntrySource#nextEntry()}.</LI>
 * </UL>
 * <BR><BR>
 * {@code SearchRequest} objects are mutable and therefore can be altered and
 * re-used for multiple requests.  Note, however, that {@code SearchRequest}
 * objects are not threadsafe and therefore a single {@code SearchRequest}
 * object instance should not be used to process multiple requests at the same
 * time.
 * <BR><BR>
 * <H2>Example</H2>
 * The following example demonstrates a simple search operation in which the
 * client performs a search to find all users in the "Sales" department and then
 * prints out the name and e-mail address for each matching user:
 * <PRE>
 *   Filter filter = Filter.createEqualityFilter("ou", "Sales");
 *
 *   SearchRequest searchRequest =
 *        new SearchRequest("dc=example,dc=com", SearchScope.SUB, filter,
 *                          "cn", "mail");
 *
 *   try
 *   {
 *     SearchResult searchResult = connection.search(searchRequest);
 *
 *     for (SearchResultEntry entry : searchResult.getSearchEntries())
 *     {
 *       String name = entry.getAttributeValue("cn");
 *       String mail = entry.getAttributeValue("mail");
 *       System.out.println(name + "\t" + mail);
 *     }
 *   }
 *   catch (LDAPSearchException lse)
 *   {
 *     System.err.println("The search failed.");
 *   }
 * </PRE>
 */
@Mutable()
@ThreadSafety(level=ThreadSafetyLevel.NOT_THREADSAFE)
public final class SearchRequest
       extends UpdatableLDAPRequest
       implements ReadOnlySearchRequest, ResponseAcceptor, ProtocolOp
{
  /**
   * The special value "*" that can be included in the set of requested
   * attributes to indicate that all user attributes should be returned.
   */
  public static final String ALL_USER_ATTRIBUTES = "*";



  /**
   * The special value "+" that can be included in the set of requested
   * attributes to indicate that all operational attributes should be returned.
   */
  public static final String ALL_OPERATIONAL_ATTRIBUTES = "+";



  /**
   * The special value "1.1" that can be included in the set of requested
   * attributes to indicate that no attributes should be returned, with the
   * exception of any other attributes explicitly named in the set of requested
   * attributes.
   */
  public static final String NO_ATTRIBUTES = "1.1";



  /**
   * The default set of requested attributes that will be used, which will
   * return all user attributes but no operational attributes.
   */
  public static final String[] REQUEST_ATTRS_DEFAULT = new String[0];



  /**
   * The serial version UID for this serializable class.
   */
  private static final long serialVersionUID = 1500219434086474893L;



  // The set of requested attributes.
  private String[] attributes;

  // Indicates whether to retrieve attribute types only or both types and
  // values.
  private boolean typesOnly;

  // The behavior to use when aliases are encountered.
  private DereferencePolicy derefPolicy;

  // The message ID from the last LDAP message sent from this request.
  private int messageID = -1;

  // The size limit for this search request.
  private int sizeLimit;

  // The time limit for this search request.
  private int timeLimit;

  // The parsed filter for this search request.
  private Filter filter;

  // The queue that will be used to receive response messages from the server.
  private final LinkedBlockingQueue<LDAPResponse> responseQueue =
       new LinkedBlockingQueue<LDAPResponse>(50);

  // The search result listener that should be used to return results
  // interactively to the requester.
  private final SearchResultListener searchResultListener;

  // The scope for this search request.
  private SearchScope scope;

  // The base DN for this search request.
  private String baseDN;



  /**
   * Creates a new search request with the provided information.  Search result
   * entries and references will be collected internally and included in the
   * {@code SearchResult} object returned when search processing is completed.
   *
   * @param  baseDN      The base DN for the search request.  It must not be
   *                     {@code null}.
   * @param  scope       The scope that specifies the range of entries that
   *                     should be examined for the search.
   * @param  filter      The string representation of the filter to use to
   *                     identify matching entries.  It must not be
   *                     {@code null}.
   * @param  attributes  The set of attributes that should be returned in
   *                     matching entries.  It may be {@code null} or empty if
   *                     the default attribute set (all user attributes) is to
   *                     be requested.
   *
   * @throws  LDAPException  If the provided filter string cannot be parsed as
   *                         an LDAP filter.
   */
  public SearchRequest(final String baseDN, final SearchScope scope,
                       final String filter, final String... attributes)
         throws LDAPException
  {
    this(null, null, baseDN, scope, DereferencePolicy.NEVER, 0, 0, false,
         Filter.create(filter), attributes);
  }



  /**
   * Creates a new search request with the provided information.  Search result
   * entries and references will be collected internally and included in the
   * {@code SearchResult} object returned when search processing is completed.
   *
   * @param  baseDN      The base DN for the search request.  It must not be
   *                     {@code null}.
   * @param  scope       The scope that specifies the range of entries that
   *                     should be examined for the search.
   * @param  filter      The string representation of the filter to use to
   *                     identify matching entries.  It must not be
   *                     {@code null}.
   * @param  attributes  The set of attributes that should be returned in
   *                     matching entries.  It may be {@code null} or empty if
   *                     the default attribute set (all user attributes) is to
   *                     be requested.
   */
  public SearchRequest(final String baseDN, final SearchScope scope,
                       final Filter filter, final String... attributes)
  {
    this(null, null, baseDN, scope, DereferencePolicy.NEVER, 0, 0, false,
         filter, attributes);
  }



  /**
   * Creates a new search request with the provided information.
   *
   * @param  searchResultListener  The search result listener that should be
   *                               used to return results to the client.  It may
   *                               be {@code null} if the search results should
   *                               be collected internally and returned in the
   *                               {@code SearchResult} object.
   * @param  baseDN                The base DN for the search request.  It must
   *                               not be {@code null}.
   * @param  scope                 The scope that specifies the range of entries
   *                               that should be examined for the search.
   * @param  filter                The string representation of the filter to
   *                               use to identify matching entries.  It must
   *                               not be {@code null}.
   * @param  attributes            The set of attributes that should be returned
   *                               in matching entries.  It may be {@code null}
   *                               or empty if the default attribute set (all
   *                               user attributes) is to be requested.
   *
   * @throws  LDAPException  If the provided filter string cannot be parsed as
   *                         an LDAP filter.
   */
  public SearchRequest(final SearchResultListener searchResultListener,
                       final String baseDN, final SearchScope scope,
                       final String filter, final String... attributes)
         throws LDAPException
  {
    this(searchResultListener, null, baseDN, scope, DereferencePolicy.NEVER, 0,
         0, false, Filter.create(filter), attributes);
  }



  /**
   * Creates a new search request with the provided information.
   *
   * @param  searchResultListener  The search result listener that should be
   *                               used to return results to the client.  It may
   *                               be {@code null} if the search results should
   *                               be collected internally and returned in the
   *                               {@code SearchResult} object.
   * @param  baseDN                The base DN for the search request.  It must
   *                               not be {@code null}.
   * @param  scope                 The scope that specifies the range of entries
   *                               that should be examined for the search.
   * @param  filter                The string representation of the filter to
   *                               use to identify matching entries.  It must
   *                               not be {@code null}.
   * @param  attributes            The set of attributes that should be returned
   *                               in matching entries.  It may be {@code null}
   *                               or empty if the default attribute set (all
   *                               user attributes) is to be requested.
   */
  public SearchRequest(final SearchResultListener searchResultListener,
                       final String baseDN, final SearchScope scope,
                       final Filter filter, final String... attributes)
  {
    this(searchResultListener, null, baseDN, scope, DereferencePolicy.NEVER, 0,
         0, false, filter, attributes);
  }



  /**
   * Creates a new search request with the provided information.  Search result
   * entries and references will be collected internally and included in the
   * {@code SearchResult} object returned when search processing is completed.
   *
   * @param  baseDN       The base DN for the search request.  It must not be
   *                      {@code null}.
   * @param  scope        The scope that specifies the range of entries that
   *                      should be examined for the search.
   * @param  derefPolicy  The dereference policy the server should use for any
   *                      aliases encountered while processing the search.
   * @param  sizeLimit    The maximum number of entries that the server should
   *                      return for the search.  A value of zero indicates that
   *                      there should be no limit.
   * @param  timeLimit    The maximum length of time in seconds that the server
   *                      should spend processing this search request.  A value
   *                      of zero indicates that there should be no limit.
   * @param  typesOnly    Indicates whether to return only attribute names in
   *                      matching entries, or both attribute names and values.
   * @param  filter       The filter to use to identify matching entries.  It
   *                      must not be {@code null}.
   * @param  attributes   The set of attributes that should be returned in
   *                      matching entries.  It may be {@code null} or empty if
   *                      the default attribute set (all user attributes) is to
   *                      be requested.
   *
   * @throws  LDAPException  If the provided filter string cannot be parsed as
   *                         an LDAP filter.
   */
  public SearchRequest(final String baseDN, final SearchScope scope,
                       final DereferencePolicy derefPolicy, final int sizeLimit,
                       final int timeLimit, final boolean typesOnly,
                       final String filter, final String... attributes)
         throws LDAPException
  {
    this(null, null, baseDN, scope, derefPolicy, sizeLimit, timeLimit,
         typesOnly, Filter.create(filter), attributes);
  }



  /**
   * Creates a new search request with the provided information.  Search result
   * entries and references will be collected internally and included in the
   * {@code SearchResult} object returned when search processing is completed.
   *
   * @param  baseDN       The base DN for the search request.  It must not be
   *                      {@code null}.
   * @param  scope        The scope that specifies the range of entries that
   *                      should be examined for the search.
   * @param  derefPolicy  The dereference policy the server should use for any
   *                      aliases encountered while processing the search.
   * @param  sizeLimit    The maximum number of entries that the server should
   *                      return for the search.  A value of zero indicates that
   *                      there should be no limit.
   * @param  timeLimit    The maximum length of time in seconds that the server
   *                      should spend processing this search request.  A value
   *                      of zero indicates that there should be no limit.
   * @param  typesOnly    Indicates whether to return only attribute names in
   *                      matching entries, or both attribute names and values.
   * @param  filter       The filter to use to identify matching entries.  It
   *                      must not be {@code null}.
   * @param  attributes   The set of attributes that should be returned in
   *                      matching entries.  It may be {@code null} or empty if
   *                      the default attribute set (all user attributes) is to
   *                      be requested.
   */
  public SearchRequest(final String baseDN, final SearchScope scope,
                       final DereferencePolicy derefPolicy, final int sizeLimit,
                       final int timeLimit, final boolean typesOnly,
                       final Filter filter, final String... attributes)
  {
    this(null, null, baseDN, scope, derefPolicy, sizeLimit, timeLimit,
         typesOnly, filter, attributes);
  }



  /**
   * Creates a new search request with the provided information.
   *
   * @param  searchResultListener  The search result listener that should be
   *                               used to return results to the client.  It may
   *                               be {@code null} if the search results should
   *                               be collected internally and returned in the
   *                               {@code SearchResult} object.
   * @param  baseDN                The base DN for the search request.  It must
   *                               not be {@code null}.
   * @param  scope                 The scope that specifies the range of entries
   *                               that should be examined for the search.
   * @param  derefPolicy           The dereference policy the server should use
   *                               for any aliases encountered while processing
   *                               the search.
   * @param  sizeLimit             The maximum number of entries that the server
   *                               should return for the search.  A value of
   *                               zero indicates that there should be no limit.
   * @param  timeLimit             The maximum length of time in seconds that
   *                               the server should spend processing this
   *                               search request.  A value of zero indicates
   *                               that there should be no limit.
   * @param  typesOnly             Indicates whether to return only attribute
   *                               names in matching entries, or both attribute
   *                               names and values.
   * @param  filter                The filter to use to identify matching
   *                               entries.  It must not be {@code null}.
   * @param  attributes            The set of attributes that should be returned
   *                               in matching entries.  It may be {@code null}
   *                               or empty if the default attribute set (all
   *                               user attributes) is to be requested.
   *
   * @throws  LDAPException  If the provided filter string cannot be parsed as
   *                         an LDAP filter.
   */
  public SearchRequest(final SearchResultListener searchResultListener,
                       final String baseDN, final SearchScope scope,
                       final DereferencePolicy derefPolicy, final int sizeLimit,
                       final int timeLimit, final boolean typesOnly,
                       final String filter, final String... attributes)
         throws LDAPException
  {
    this(searchResultListener, null, baseDN, scope, derefPolicy, sizeLimit,
         timeLimit, typesOnly, Filter.create(filter), attributes);
  }



  /**
   * Creates a new search request with the provided information.
   *
   * @param  searchResultListener  The search result listener that should be
   *                               used to return results to the client.  It may
   *                               be {@code null} if the search results should
   *                               be collected internally and returned in the
   *                               {@code SearchResult} object.
   * @param  baseDN                The base DN for the search request.  It must
   *                               not be {@code null}.
   * @param  scope                 The scope that specifies the range of entries
   *                               that should be examined for the search.
   * @param  derefPolicy           The dereference policy the server should use
   *                               for any aliases encountered while processing
   *                               the search.
   * @param  sizeLimit             The maximum number of entries that the server
   *                               should return for the search.  A value of
   *                               zero indicates that there should be no limit.
   * @param  timeLimit             The maximum length of time in seconds that
   *                               the server should spend processing this
   *                               search request.  A value of zero indicates
   *                               that there should be no limit.
   * @param  typesOnly             Indicates whether to return only attribute
   *                               names in matching entries, or both attribute
   *                               names and values.
   * @param  filter                The filter to use to identify matching
   *                               entries.  It must not be {@code null}.
   * @param  attributes            The set of attributes that should be returned
   *                               in matching entries.  It may be {@code null}
   *                               or empty if the default attribute set (all
   *                               user attributes) is to be requested.
   */
  public SearchRequest(final SearchResultListener searchResultListener,
                       final String baseDN, final SearchScope scope,
                       final DereferencePolicy derefPolicy, final int sizeLimit,
                       final int timeLimit, final boolean typesOnly,
                       final Filter filter, final String... attributes)
  {
    this(searchResultListener, null, baseDN, scope, derefPolicy, sizeLimit,
         timeLimit, typesOnly, filter, attributes);
  }



  /**
   * Creates a new search request with the provided information.
   *
   * @param  searchResultListener  The search result listener that should be
   *                               used to return results to the client.  It may
   *                               be {@code null} if the search results should
   *                               be collected internally and returned in the
   *                               {@code SearchResult} object.
   * @param  controls              The set of controls to include in the
   *                               request.  It may be {@code null} or empty if
   *                               no controls should be included in the
   *                               request.
   * @param  baseDN                The base DN for the search request.  It must
   *                               not be {@code null}.
   * @param  scope                 The scope that specifies the range of entries
   *                               that should be examined for the search.
   * @param  derefPolicy           The dereference policy the server should use
   *                               for any aliases encountered while processing
   *                               the search.
   * @param  sizeLimit             The maximum number of entries that the server
   *                               should return for the search.  A value of
   *                               zero indicates that there should be no limit.
   * @param  timeLimit             The maximum length of time in seconds that
   *                               the server should spend processing this
   *                               search request.  A value of zero indicates
   *                               that there should be no limit.
   * @param  typesOnly             Indicates whether to return only attribute
   *                               names in matching entries, or both attribute
   *                               names and values.
   * @param  filter                The filter to use to identify matching
   *                               entries.  It must not be {@code null}.
   * @param  attributes            The set of attributes that should be returned
   *                               in matching entries.  It may be {@code null}
   *                               or empty if the default attribute set (all
   *                               user attributes) is to be requested.
   *
   * @throws  LDAPException  If the provided filter string cannot be parsed as
   *                         an LDAP filter.
   */
  public SearchRequest(final SearchResultListener searchResultListener,
                       final Control[] controls, final String baseDN,
                       final SearchScope scope,
                       final DereferencePolicy derefPolicy, final int sizeLimit,
                       final int timeLimit, final boolean typesOnly,
                       final String filter, final String... attributes)
         throws LDAPException
  {
    this(searchResultListener, controls, baseDN, scope, derefPolicy, sizeLimit,
         timeLimit, typesOnly, Filter.create(filter), attributes);
  }



  /**
   * Creates a new search request with the provided information.
   *
   * @param  searchResultListener  The search result listener that should be
   *                               used to return results to the client.  It may
   *                               be {@code null} if the search results should
   *                               be collected internally and returned in the
   *                               {@code SearchResult} object.
   * @param  controls              The set of controls to include in the
   *                               request.  It may be {@code null} or empty if
   *                               no controls should be included in the
   *                               request.
   * @param  baseDN                The base DN for the search request.  It must
   *                               not be {@code null}.
   * @param  scope                 The scope that specifies the range of entries
   *                               that should be examined for the search.
   * @param  derefPolicy           The dereference policy the server should use
   *                               for any aliases encountered while processing
   *                               the search.
   * @param  sizeLimit             The maximum number of entries that the server
   *                               should return for the search.  A value of
   *                               zero indicates that there should be no limit.
   * @param  timeLimit             The maximum length of time in seconds that
   *                               the server should spend processing this
   *                               search request.  A value of zero indicates
   *                               that there should be no limit.
   * @param  typesOnly             Indicates whether to return only attribute
   *                               names in matching entries, or both attribute
   *                               names and values.
   * @param  filter                The filter to use to identify matching
   *                               entries.  It must not be {@code null}.
   * @param  attributes            The set of attributes that should be returned
   *                               in matching entries.  It may be {@code null}
   *                               or empty if the default attribute set (all
   *                               user attributes) is to be requested.
   */
  public SearchRequest(final SearchResultListener searchResultListener,
                       final Control[] controls, final String baseDN,
                       final SearchScope scope,
                       final DereferencePolicy derefPolicy, final int sizeLimit,
                       final int timeLimit, final boolean typesOnly,
                       final Filter filter, final String... attributes)
  {
    super(controls);

    ensureNotNull(baseDN, filter);

    this.baseDN               = baseDN;
    this.scope                = scope;
    this.derefPolicy          = derefPolicy;
    this.typesOnly            = typesOnly;
    this.filter               = filter;
    this.searchResultListener = searchResultListener;

    if (sizeLimit < 0)
    {
      this.sizeLimit = 0;
    }
    else
    {
      this.sizeLimit = sizeLimit;
    }

    if (timeLimit < 0)
    {
      this.timeLimit = 0;
    }
    else
    {
      this.timeLimit = timeLimit;
    }

    if (attributes == null)
    {
      this.attributes = REQUEST_ATTRS_DEFAULT;
    }
    else
    {
      this.attributes = attributes;
    }
  }



  /**
   * {@inheritDoc}
   */
  public String getBaseDN()
  {
    return baseDN;
  }



  /**
   * Specifies the base DN for this search request.
   *
   * @param  baseDN  The base DN for this search request.  It must not be
   *                 {@code null}.
   */
  public void setBaseDN(final String baseDN)
  {
    ensureNotNull(baseDN);

    this.baseDN = baseDN;
  }



  /**
   * Specifies the base DN for this search request.
   *
   * @param  baseDN  The base DN for this search request.  It must not be
   *                 {@code null}.
   */
  public void setBaseDN(final DN baseDN)
  {
    ensureNotNull(baseDN);

    this.baseDN = baseDN.toString();
  }



  /**
   * {@inheritDoc}
   */
  public SearchScope getScope()
  {
    return scope;
  }



  /**
   * Specifies the scope for this search request.
   *
   * @param  scope  The scope for this search request.
   */
  public void setScope(final SearchScope scope)
  {
    this.scope = scope;
  }



  /**
   * {@inheritDoc}
   */
  public DereferencePolicy getDereferencePolicy()
  {
    return derefPolicy;
  }



  /**
   * Specifies the dereference policy that should be used by the server for any
   * aliases encountered during search processing.
   *
   * @param  derefPolicy  The dereference policy that should be used by the
   *                      server for any aliases encountered during search
   *                      processing.
   */
  public void setDerefPolicy(final DereferencePolicy derefPolicy)
  {
    this.derefPolicy = derefPolicy;
  }



  /**
   * {@inheritDoc}
   */
  public int getSizeLimit()
  {
    return sizeLimit;
  }



  /**
   * Specifies the maximum number of entries that should be returned by the
   * server when processing this search request.  A value of zero indicates that
   * there should be no limit.
   *
   * @param  sizeLimit  The maximum number of entries that should be returned by
   *                    the server when processing this search request.
   */
  public void setSizeLimit(final int sizeLimit)
  {
    if (sizeLimit < 0)
    {
      this.sizeLimit = 0;
    }
    else
    {
      this.sizeLimit = sizeLimit;
    }
  }



  /**
   * {@inheritDoc}
   */
  public int getTimeLimitSeconds()
  {
    return timeLimit;
  }



  /**
   * Specifies the maximum length of time in seconds that the server should
   * spend processing this search request.  A value of zero indicates that there
   * should be no limit.
   *
   * @param  timeLimit  The maximum length of time in seconds that the server
   *                    should spend processing this search request.
   */
  public void setTimeLimitSeconds(final int timeLimit)
  {
    if (timeLimit < 0)
    {
      this.timeLimit = 0;
    }
    else
    {
      this.timeLimit = timeLimit;
    }
  }



  /**
   * {@inheritDoc}
   */
  public boolean typesOnly()
  {
    return typesOnly;
  }



  /**
   * Specifies whether the server should return only attribute names in matching
   * entries, rather than both names and values.
   *
   * @param  typesOnly  Specifies whether the server should return only
   *                    attribute names in matching entries, rather than both
   *                    names and values.
   */
  public void setTypesOnly(final boolean typesOnly)
  {
    this.typesOnly = typesOnly;
  }



  /**
   * {@inheritDoc}
   */
  public Filter getFilter()
  {
    return filter;
  }



  /**
   * Specifies the filter that should be used to identify matching entries.
   *
   * @param  filter  The string representation for the filter that should be
   *                 used to identify matching entries.  It must not be
   *                 {@code null}.
   *
   * @throws  LDAPException  If the provided filter string cannot be parsed as a
   *                         search filter.
   */
  public void setFilter(final String filter)
         throws LDAPException
  {
    ensureNotNull(filter);

    this.filter = Filter.create(filter);
  }



  /**
   * Specifies the filter that should be used to identify matching entries.
   *
   * @param  filter  The filter that should be used to identify matching
   *                 entries.  It must not be {@code null}.
   */
  public void setFilter(final Filter filter)
  {
    ensureNotNull(filter);

    this.filter = filter;
  }



  /**
   * Retrieves the set of requested attributes to include in matching entries.
   * The caller must not attempt to alter the contents of the array.
   *
   * @return  The set of requested attributes to include in matching entries, or
   *          an empty array if the default set of attributes (all user
   *          attributes but no operational attributes) should be requested.
   */
  public String[] getAttributes()
  {
    return attributes;
  }



  /**
   * {@inheritDoc}
   */
  public List<String> getAttributeList()
  {
    return Collections.unmodifiableList(Arrays.asList(attributes));
  }



  /**
   * Specifies the set of requested attributes to include in matching entries.
   *
   * @param  attributes  The set of requested attributes to include in matching
   *                     entries.  It may be {@code null} if the default set of
   *                     attributes (all user attributes but no operational
   *                     attributes) should be requested.
   */
  public void setAttributes(final String... attributes)
  {
    if (attributes == null)
    {
      this.attributes = REQUEST_ATTRS_DEFAULT;
    }
    else
    {
      this.attributes = attributes;
    }
  }



  /**
   * Specifies the set of requested attributes to include in matching entries.
   *
   * @param  attributes  The set of requested attributes to include in matching
   *                     entries.  It may be {@code null} if the default set of
   *                     attributes (all user attributes but no operational
   *                     attributes) should be requested.
   */
  public void setAttributes(final List<String> attributes)
  {
    if (attributes == null)
    {
      this.attributes = REQUEST_ATTRS_DEFAULT;
    }
    else
    {
      this.attributes = new String[attributes.size()];
      for (int i=0; i < this.attributes.length; i++)
      {
        this.attributes[i] = attributes.get(i);
      }
    }
  }



  /**
   * Retrieves the search result listener for this search request, if available.
   *
   * @return  The search result listener for this search request, or
   *          {@code null} if none has been configured.
   */
  SearchResultListener getSearchResultListener()
  {
    return searchResultListener;
  }



  /**
   * {@inheritDoc}
   */
  public byte getProtocolOpType()
  {
    return LDAPMessage.PROTOCOL_OP_TYPE_SEARCH_REQUEST;
  }



  /**
   * {@inheritDoc}
   */
  public void writeTo(final ASN1Buffer writer)
  {
    final ASN1BufferSequence requestSequence =
         writer.beginSequence(LDAPMessage.PROTOCOL_OP_TYPE_SEARCH_REQUEST);
    writer.addOctetString(baseDN);
    writer.addEnumerated(scope.intValue());
    writer.addEnumerated(derefPolicy.intValue());
    writer.addInteger(sizeLimit);
    writer.addInteger(timeLimit);
    writer.addBoolean(typesOnly);
    filter.writeTo(writer);

    final ASN1BufferSequence attrSequence = writer.beginSequence();
    for (final String s : attributes)
    {
      writer.addOctetString(s);
    }
    attrSequence.end();
    requestSequence.end();
  }



  /**
   * Encodes the search request protocol op to an ASN.1 element.
   *
   * @return  The ASN.1 element with the encoded search request protocol op.
   */
  ASN1Element encodeProtocolOp()
  {
    // Create the search request protocol op.
    final ASN1Element[] attrElements = new ASN1Element[attributes.length];
    for (int i=0; i < attrElements.length; i++)
    {
      attrElements[i] = new ASN1OctetString(attributes[i]);
    }

    final ASN1Element[] protocolOpElements =
    {
      new ASN1OctetString(baseDN),
      new ASN1Enumerated(scope.intValue()),
      new ASN1Enumerated(derefPolicy.intValue()),
      new ASN1Integer(sizeLimit),
      new ASN1Integer(timeLimit),
      new ASN1Boolean(typesOnly),
      filter.encode(),
      new ASN1Sequence(attrElements)
    };

    return new ASN1Sequence(LDAPMessage.PROTOCOL_OP_TYPE_SEARCH_REQUEST,
                            protocolOpElements);
  }



  /**
   * Sends this search request to the directory server over the provided
   * connection and returns the associated response.  The search result entries
   * and references will either be collected and returned in the
   * {@code SearchResult} object that is returned, or will be interactively
   * returned via the {@code SearchResultListener} interface.
   *
   * @param  connection  The connection to use to communicate with the directory
   *                     server.
   * @param  depth       The current referral depth for this request.  It should
   *                     always be one for the initial request, and should only
   *                     be incremented when following referrals.
   *
   * @return  An object that provides information about the result of the
   *          search processing, potentially including the sets of matching
   *          entries and/or search references.
   *
   * @throws  LDAPException  If a problem occurs while sending the request or
   *                         reading the response.
   */
  @Override()
  protected SearchResult process(final LDAPConnection connection,
                                 final int depth)
            throws LDAPException
  {
    if (connection.synchronousMode())
    {
      return processSync(connection, depth);
    }

    final long requestTime = System.nanoTime();
    processAsync(connection, null);

    try
    {
      // Wait for and process the response.
      final LinkedList<SearchResultEntry> entryList;
      final LinkedList<SearchResultReference> referenceList;
      if (searchResultListener == null)
      {
        entryList     = new LinkedList<SearchResultEntry>();
        referenceList = new LinkedList<SearchResultReference>();
      }
      else
      {
        entryList     = null;
        referenceList = null;
      }

      int numEntries    = 0;
      int numReferences = 0;
      ResultCode intermediateResultCode = ResultCode.SUCCESS;
      while (true)
      {
        final LDAPResponse response;
        try
        {
          final long responseTimeout = getResponseTimeoutMillis(connection);
          if (responseTimeout > 0)
          {
            response =
                 responseQueue.poll(responseTimeout, TimeUnit.MILLISECONDS);
          }
          else
          {
            response = responseQueue.take();
          }
        }
        catch (InterruptedException ie)
        {
          debugException(ie);
          throw new LDAPException(ResultCode.LOCAL_ERROR,
               ERR_SEARCH_INTERRUPTED.get(connection.getHostPort()), ie);
        }

        if (response == null)
        {
          final SearchResult searchResult =
               new SearchResult(messageID, ResultCode.TIMEOUT,
                    ERR_CLIENT_TIMEOUT.get(connection.getHostPort()), null,
                    null, entryList, referenceList, numEntries, numReferences,
                    null);
          throw new LDAPSearchException(searchResult);
        }

        if (response instanceof ConnectionClosedResponse)
        {
          final ConnectionClosedResponse ccr =
               (ConnectionClosedResponse) response;
          final String message = ccr.getMessage();
          if (message == null)
          {
            // The connection was closed while waiting for the response.
            final SearchResult searchResult =
                 new SearchResult(messageID, ResultCode.SERVER_DOWN,
                      ERR_CONN_CLOSED_WAITING_FOR_SEARCH_RESPONSE.get(
                           connection.getHostPort(), toString()),
                      null, null, entryList, referenceList, numEntries,
                      numReferences, null);
            throw new LDAPSearchException(searchResult);
          }
          else
          {
            // The connection was closed while waiting for the response.
            final SearchResult searchResult =
                 new SearchResult(messageID, ResultCode.SERVER_DOWN,
                      ERR_CONN_CLOSED_WAITING_FOR_SEARCH_RESPONSE_WITH_MESSAGE.
                           get(connection.getHostPort(), toString(), message),
                      null, null, entryList, referenceList, numEntries,
                      numReferences, null);
            throw new LDAPSearchException(searchResult);
          }
        }
        else if (response instanceof SearchResultEntry)
        {
          final SearchResultEntry searchEntry = (SearchResultEntry) response;
          numEntries++;
          if (searchResultListener == null)
          {
            entryList.add(searchEntry);
          }
          else
          {
            searchResultListener.searchEntryReturned(searchEntry);
          }
        }
        else if (response instanceof SearchResultReference)
        {
          final SearchResultReference searchReference =
               (SearchResultReference) response;
          if (followReferrals(connection))
          {
            final LDAPResult result = followSearchReference(messageID,
                 searchReference, connection, depth);
            if (! result.getResultCode().equals(ResultCode.SUCCESS))
            {
              // We couldn't follow the reference.  We don't want to fail the
              // entire search because of this right now, so treat it as if
              // referral following had not been enabled.  Also, set the
              // intermediate result code to match that of the result.
              numReferences++;
              if (searchResultListener == null)
              {
                referenceList.add(searchReference);
              }
              else
              {
                searchResultListener.searchReferenceReturned(searchReference);
              }

              if (intermediateResultCode.equals(ResultCode.SUCCESS))
              {
                intermediateResultCode = result.getResultCode();
              }
            }
            else if (result instanceof SearchResult)
            {
              final SearchResult searchResult = (SearchResult) result;
              numEntries += searchResult.getEntryCount();
              if (searchResultListener == null)
              {
                entryList.addAll(searchResult.getSearchEntries());
              }
            }
          }
          else
          {
            numReferences++;
            if (searchResultListener == null)
            {
              referenceList.add(searchReference);
            }
            else
            {
              searchResultListener.searchReferenceReturned(searchReference);
            }
          }
        }
        else
        {
          connection.getConnectionStatistics().incrementNumSearchResponses(
               numEntries, numReferences,
               (System.nanoTime() - requestTime));
          SearchResult result = (SearchResult) response;
          result.setCounts(numEntries, entryList, numReferences, referenceList);

          if ((result.getResultCode().equals(ResultCode.REFERRAL)) &&
              followReferrals(connection))
          {
            if (depth >=
                connection.getConnectionOptions().getReferralHopLimit())
            {
              return new SearchResult(messageID,
                                      ResultCode.REFERRAL_LIMIT_EXCEEDED,
                                      ERR_TOO_MANY_REFERRALS.get(),
                                      result.getMatchedDN(),
                                      result.getReferralURLs(), entryList,
                                      referenceList, numEntries,
                                      numReferences,
                                      result.getResponseControls());
            }

            result = followReferral(result, connection, depth);
          }

          if ((result.getResultCode().equals(ResultCode.SUCCESS)) &&
              (! intermediateResultCode.equals(ResultCode.SUCCESS)))
          {
            return new SearchResult(messageID, intermediateResultCode,
                                    result.getDiagnosticMessage(),
                                    result.getMatchedDN(),
                                    result.getReferralURLs(),
                                    entryList, referenceList, numEntries,
                                    numReferences,
                                    result.getResponseControls());
          }

          return result;
        }
      }
    }
    finally
    {
      connection.deregisterResponseAcceptor(messageID);
    }
  }



  /**
   * Sends this search request to the directory server over the provided
   * connection and returns the message ID for the request.
   *
   * @param  connection      The connection to use to communicate with the
   *                         directory server.
   * @param  resultListener  The async result listener that is to be notified
   *                         when the response is received.  It may be
   *                         {@code null} only if the result is to be processed
   *                         by this class.
   *
   * @return  The LDAP message ID for the search request that was sent to the
   *          server.
   *
   * @throws  LDAPException  If a problem occurs while sending the request.
   */
  int processAsync(final LDAPConnection connection,
                   final AsyncSearchResultListener resultListener)
      throws LDAPException
  {
    // Create the LDAP message.
    messageID = connection.nextMessageID();
    final LDAPMessage message = new LDAPMessage(messageID, this, getControls());


    // If the provided async result listener is {@code null}, then we'll use
    // this class as the message acceptor.  Otherwise, create an async helper
    // and use it as the message acceptor.
    if (resultListener == null)
    {
      connection.registerResponseAcceptor(messageID, this);
    }
    else
    {
      final AsyncSearchHelper helper = new AsyncSearchHelper(connection,
           resultListener, getIntermediateResponseListener());
      connection.registerResponseAcceptor(messageID, helper);
    }


    // Send the request to the server.
    try
    {
      debugLDAPRequest(this);
      connection.getConnectionStatistics().incrementNumSearchRequests();
      connection.sendMessage(message);
      return messageID;
    }
    catch (LDAPException le)
    {
      debugException(le);

      connection.deregisterResponseAcceptor(messageID);
      throw le;
    }
  }



  /**
   * Processes this search operation in synchronous mode, in which the same
   * thread will send the request and read the response.
   *
   * @param  connection  The connection to use to communicate with the directory
   *                     server.
   * @param  depth       The current referral depth for this request.  It should
   *                     always be one for the initial request, and should only
   *                     be incremented when following referrals.
   *
   * @return  An LDAP result object that provides information about the result
   *          of the search processing.
   *
   * @throws  LDAPException  If a problem occurs while sending the request or
   *                         reading the response.
   */
  private SearchResult processSync(final LDAPConnection connection,
                                   final int depth)
          throws LDAPException
  {
    // Create the LDAP message.
    messageID = connection.nextMessageID();
    final LDAPMessage message =
         new LDAPMessage(messageID,  this, getControls());


    // Set the appropriate timeout on the socket.
    try
    {
      connection.getConnectionInternals().getSocket().setSoTimeout(
           (int) getResponseTimeoutMillis(connection));
    }
    catch (Exception e)
    {
      debugException(e);
    }


    // Send the request to the server.
    final long requestTime = System.nanoTime();
    debugLDAPRequest(this);
    connection.getConnectionStatistics().incrementNumSearchRequests();
    connection.sendMessage(message);

    final LinkedList<SearchResultEntry> entryList;
    final LinkedList<SearchResultReference> referenceList;
    if (searchResultListener == null)
    {
      entryList     = new LinkedList<SearchResultEntry>();
      referenceList = new LinkedList<SearchResultReference>();
    }
    else
    {
      entryList     = null;
      referenceList = null;
    }

    int numEntries    = 0;
    int numReferences = 0;
    ResultCode intermediateResultCode = ResultCode.SUCCESS;
    while (true)
    {
      final LDAPResponse response = connection.readResponse(messageID);

      if (response == null)
      {
        throw new LDAPException(ResultCode.TIMEOUT,
             ERR_CLIENT_TIMEOUT.get(connection.getHostPort()));
      }
      else if (response instanceof ConnectionClosedResponse)
      {
        final ConnectionClosedResponse ccr =
             (ConnectionClosedResponse) response;
        final String msg = ccr.getMessage();
        if (msg == null)
        {
          // The connection was closed while waiting for the response.
          final SearchResult searchResult =
               new SearchResult(messageID, ResultCode.SERVER_DOWN,
                    ERR_CONN_CLOSED_WAITING_FOR_SEARCH_RESPONSE.get(
                         connection.getHostPort(), toString()),
                    null, null, entryList, referenceList, numEntries,
                    numReferences, null);
          throw new LDAPSearchException(searchResult);
        }
        else
        {
          // The connection was closed while waiting for the response.
          final SearchResult searchResult =
               new SearchResult(messageID, ResultCode.SERVER_DOWN,
                    ERR_CONN_CLOSED_WAITING_FOR_SEARCH_RESPONSE_WITH_MESSAGE.
                         get(connection.getHostPort(), toString(), msg),
                    null, null, entryList, referenceList, numEntries,
                    numReferences, null);
          throw new LDAPSearchException(searchResult);
        }
      }
      else if (response instanceof SearchResultEntry)
      {
        final SearchResultEntry searchEntry = (SearchResultEntry) response;
        numEntries++;
        if (searchResultListener == null)
        {
          entryList.add(searchEntry);
        }
        else
        {
          searchResultListener.searchEntryReturned(searchEntry);
        }
      }
      else if (response instanceof SearchResultReference)
      {
        final SearchResultReference searchReference =
             (SearchResultReference) response;
        if (followReferrals(connection))
        {
          final LDAPResult result = followSearchReference(messageID,
               searchReference, connection, depth);
          if (! result.getResultCode().equals(ResultCode.SUCCESS))
          {
            // We couldn't follow the reference.  We don't want to fail the
            // entire search because of this right now, so treat it as if
            // referral following had not been enabled.  Also, set the
            // intermediate result code to match that of the result.
            numReferences++;
            if (searchResultListener == null)
            {
              referenceList.add(searchReference);
            }
            else
            {
              searchResultListener.searchReferenceReturned(searchReference);
            }

            if (intermediateResultCode.equals(ResultCode.SUCCESS))
            {
              intermediateResultCode = result.getResultCode();
            }
          }
          else if (result instanceof SearchResult)
          {
            final SearchResult searchResult = (SearchResult) result;
            numEntries += searchResult.getEntryCount();
            if (searchResultListener == null)
            {
              entryList.addAll(searchResult.getSearchEntries());
            }
          }
        }
        else
        {
          numReferences++;
          if (searchResultListener == null)
          {
            referenceList.add(searchReference);
          }
          else
          {
            searchResultListener.searchReferenceReturned(searchReference);
          }
        }
      }
      else
      {
        return handleResponse(connection, response, requestTime, depth,
                              numEntries, numReferences, entryList,
                              referenceList, intermediateResultCode);
      }
    }
  }



  /**
   * Performs the necessary processing for handling a response.
   *
   * @param  connection              The connection used to read the response.
   * @param  response                The response to be processed.
   * @param  requestTime             The time the request was sent to the
   *                                 server.
   * @param  depth                   The current referral depth for this
   *                                 request.  It should always be one for the
   *                                 initial request, and should only be
   *                                 incremented when following referrals.
   * @param  numEntries              The number of entries received from the
   *                                 server.
   * @param  numReferences           The number of references received from
   *                                 the server.
   * @param  entryList               The list of search result entries received
   *                                 from the server, if applicable.
   * @param  referenceList           The list of search result references
   *                                 received from the server, if applicable.
   * @param  intermediateResultCode  The intermediate result code so far for the
   *                                 search operation.
   *
   * @return  The search result.
   *
   * @throws  LDAPException  If a problem occurs.
   */
  private SearchResult handleResponse(final LDAPConnection connection,
               final LDAPResponse response, final long requestTime,
               final int depth, final int numEntries, final int numReferences,
               final List<SearchResultEntry> entryList,
               final List<SearchResultReference> referenceList,
               final ResultCode intermediateResultCode)
          throws LDAPException
  {
    connection.getConnectionStatistics().incrementNumSearchResponses(
         numEntries, numReferences,
         (System.nanoTime() - requestTime));
    SearchResult result = (SearchResult) response;
    result.setCounts(numEntries, entryList, numReferences, referenceList);

    if ((result.getResultCode().equals(ResultCode.REFERRAL)) &&
        followReferrals(connection))
    {
      if (depth >=
          connection.getConnectionOptions().getReferralHopLimit())
      {
        return new SearchResult(messageID,
                                ResultCode.REFERRAL_LIMIT_EXCEEDED,
                                ERR_TOO_MANY_REFERRALS.get(),
                                result.getMatchedDN(),
                                result.getReferralURLs(), entryList,
                                referenceList, numEntries,
                                numReferences,
                                result.getResponseControls());
      }

      result = followReferral(result, connection, depth);
    }

    if ((result.getResultCode().equals(ResultCode.SUCCESS)) &&
        (! intermediateResultCode.equals(ResultCode.SUCCESS)))
    {
      return new SearchResult(messageID, intermediateResultCode,
                              result.getDiagnosticMessage(),
                              result.getMatchedDN(),
                              result.getReferralURLs(),
                              entryList, referenceList, numEntries,
                              numReferences,
                              result.getResponseControls());
    }

    return result;
  }



  /**
   * Attempts to follow a search result reference to continue a search in a
   * remote server.
   *
   * @param  messageID        The message ID for the LDAP message that is
   *                          associated with this result.
   * @param  searchReference  The search result reference to follow.
   * @param  connection       The connection on which the reference was
   *                          received.
   * @param  depth            The number of referrals followed in the course of
   *                          processing this request.
   *
   * @return  The result of attempting to follow the search result reference.
   *
   * @throws  LDAPException  If a problem occurs while attempting to establish
   *                         the referral connection, sending the request, or
   *                         reading the result.
   */
  private LDAPResult followSearchReference(final int messageID,
                          final SearchResultReference searchReference,
                          final LDAPConnection connection, final int depth)
          throws LDAPException
  {
    for (final String urlString : searchReference.getReferralURLs())
    {
      try
      {
        final LDAPURL referralURL = new LDAPURL(urlString);
        final String host = referralURL.getHost();

        if (host == null)
        {
          // We can't handle a referral in which there is no host.
          continue;
        }

        final String requestBaseDN;
        if (referralURL.baseDNProvided())
        {
          requestBaseDN = referralURL.getBaseDN().toString();
        }
        else
        {
          requestBaseDN = baseDN;
        }

        final SearchScope requestScope;
        if (referralURL.scopeProvided())
        {
          requestScope = referralURL.getScope();
        }
        else
        {
          requestScope = scope;
        }

        final Filter requestFilter;
        if (referralURL.filterProvided())
        {
          requestFilter = referralURL.getFilter();
        }
        else
        {
          requestFilter = filter;
        }


        final SearchRequest searchRequest =
             new SearchRequest(searchResultListener, getControls(),
                               requestBaseDN, requestScope, derefPolicy,
                               sizeLimit, timeLimit, typesOnly, requestFilter,
                               attributes);

        final LDAPConnection referralConn =
             connection.getReferralConnection(referralURL, connection);

        try
        {
          return searchRequest.process(referralConn, depth+1);
        }
        finally
        {
          referralConn.setDisconnectInfo(DisconnectType.REFERRAL, null, null);
          referralConn.close();
        }
      }
      catch (LDAPException le)
      {
        debugException(le);

        if (le.getResultCode().equals(ResultCode.REFERRAL_LIMIT_EXCEEDED))
        {
          throw le;
        }
      }
    }

    // If we've gotten here, then we could not follow any of the referral URLs,
    // so we'll create a failure result.
    return new SearchResult(messageID, ResultCode.REFERRAL, null, null,
                            searchReference.getReferralURLs(), 0, 0, null);
  }



  /**
   * Attempts to follow a referral to perform an add operation in the target
   * server.
   *
   * @param  referralResult  The LDAP result object containing information about
   *                         the referral to follow.
   * @param  connection      The connection on which the referral was received.
   * @param  depth           The number of referrals followed in the course of
   *                         processing this request.
   *
   * @return  The result of attempting to process the add operation by following
   *          the referral.
   *
   * @throws  LDAPException  If a problem occurs while attempting to establish
   *                         the referral connection, sending the request, or
   *                         reading the result.
   */
  private SearchResult followReferral(final SearchResult referralResult,
                                      final LDAPConnection connection,
                                      final int depth)
          throws LDAPException
  {
    for (final String urlString : referralResult.getReferralURLs())
    {
      try
      {
        final LDAPURL referralURL = new LDAPURL(urlString);
        final String host = referralURL.getHost();

        if (host == null)
        {
          // We can't handle a referral in which there is no host.
          continue;
        }

        final String requestBaseDN;
        if (referralURL.baseDNProvided())
        {
          requestBaseDN = referralURL.getBaseDN().toString();
        }
        else
        {
          requestBaseDN = baseDN;
        }

        final SearchScope requestScope;
        if (referralURL.scopeProvided())
        {
          requestScope = referralURL.getScope();
        }
        else
        {
          requestScope = scope;
        }

        final Filter requestFilter;
        if (referralURL.filterProvided())
        {
          requestFilter = referralURL.getFilter();
        }
        else
        {
          requestFilter = filter;
        }


        final SearchRequest searchRequest =
             new SearchRequest(searchResultListener, getControls(),
                               requestBaseDN, requestScope, derefPolicy,
                               sizeLimit, timeLimit, typesOnly, requestFilter,
                               attributes);

        final LDAPConnection referralConn =
             connection.getReferralConnection(referralURL, connection);
        try
        {
          return searchRequest.process(referralConn, depth+1);
        }
        finally
        {
          referralConn.setDisconnectInfo(DisconnectType.REFERRAL, null, null);
          referralConn.close();
        }
      }
      catch (LDAPException le)
      {
        debugException(le);

        if (le.getResultCode().equals(ResultCode.REFERRAL_LIMIT_EXCEEDED))
        {
          throw le;
        }
      }
    }

    // If we've gotten here, then we could not follow any of the referral URLs,
    // so we'll just return the original referral result.
    return referralResult;
  }



  /**
   * {@inheritDoc}
   */
  @InternalUseOnly()
  public void responseReceived(final LDAPResponse response)
         throws LDAPException
  {
    try
    {
      responseQueue.put(response);
    }
    catch (Exception e)
    {
      debugException(e);
      throw new LDAPException(ResultCode.LOCAL_ERROR,
           ERR_EXCEPTION_HANDLING_RESPONSE.get(getExceptionMessage(e)), e);
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public int getLastMessageID()
  {
    return messageID;
  }



  /**
   * {@inheritDoc}
   */
  public SearchRequest duplicate()
  {
    return duplicate(getControls());
  }



  /**
   * {@inheritDoc}
   */
  public SearchRequest duplicate(final Control[] controls)
  {
    final SearchRequest r = new SearchRequest(searchResultListener, controls,
         baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter,
         attributes);
    if (followReferralsInternal() != null)
    {
      r.setFollowReferrals(followReferralsInternal());
    }

    return r;
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public void toString(final StringBuilder buffer)
  {
    buffer.append("SearchRequest(baseDN='");
    buffer.append(baseDN);
    buffer.append("', scope=");
    buffer.append(scope);
    buffer.append(", deref=");
    buffer.append(derefPolicy);
    buffer.append(", sizeLimit=");
    buffer.append(sizeLimit);
    buffer.append(", timeLimit=");
    buffer.append(timeLimit);
    buffer.append(", filter='");
    buffer.append(filter);
    buffer.append("', attrs={");

    for (int i=0; i < attributes.length; i++)
    {
      if (i > 0)
      {
        buffer.append(", ");
      }

      buffer.append(attributes[i]);
    }
    buffer.append('}');

    final Control[] controls = getControls();
    if (controls.length > 0)
    {
      buffer.append(", controls={");
      for (int i=0; i < controls.length; i++)
      {
        if (i > 0)
        {
          buffer.append(", ");
        }

        buffer.append(controls[i]);
      }
      buffer.append('}');
    }

    buffer.append(')');
  }
}
