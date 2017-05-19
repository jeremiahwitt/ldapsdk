/*
 * Copyright 2015-2017 Ping Identity Corporation
 * All Rights Reserved.
 */
/*
 * Copyright (C) 2015-2017 Ping Identity Corporation
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



import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.util.NotExtensible;
import com.unboundid.util.NotMutable;
import com.unboundid.util.ThreadSafety;
import com.unboundid.util.ThreadSafetyLevel;



/**
 * This class defines an exception that can be thrown if the server returns an
 * extended response that indicates that the operation did not complete
 * successfully.  This may be used to obtain access to any response OID and/or
 * value that may have been included in the extended result.
 */
@NotExtensible()
@NotMutable()
@ThreadSafety(level=ThreadSafetyLevel.COMPLETELY_THREADSAFE)
public class LDAPExtendedOperationException
       extends LDAPException
{
  /**
   * The serial version UID for this serializable class.
   */
  private static final long serialVersionUID = -5674215690199642408L;



  // The extended result for this exception.
  private final ExtendedResult extendedResult;



  /**
   * Creates a new LDAP extended operation exception from the provided extended
   * result.
   *
   * @param  extendedResult  The extended result to use to create this
   *                         exception.
   */
  public LDAPExtendedOperationException(final ExtendedResult extendedResult)
  {
    super(extendedResult);

    this.extendedResult = extendedResult;
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public LDAPResult toLDAPResult()
  {
    return extendedResult;
  }



  /**
   * Retrieves the extended result that was returned by the server.
   *
   * @return  The extended result that was returned by the server.
   */
  public ExtendedResult getExtendedResult()
  {
    return extendedResult;
  }



  /**
   * Retrieves the response OID from the extended result, if any.
   *
   * @return  The response OID from the extended result, or {@code null} if the
   *          result did not include an OID.
   */
  public String getResponseOID()
  {
    return extendedResult.getOID();
  }



  /**
   * Retrieves the response value from the extended result, if any.
   *
   * @return  The response value from the extended result, or {@code null} if
   *          the result did not include a value.
   */
  public ASN1OctetString getResponseValue()
  {
    return extendedResult.getValue();
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public void toString(final StringBuilder buffer)
  {
    buffer.append("LDAPException(resultCode=");
    buffer.append(getResultCode());

    final String errorMessage = getMessage();
    if (errorMessage != null)
    {
      buffer.append(", errorMessage='");
      buffer.append(errorMessage);
      buffer.append('\'');
    }

    final String responseOID = getResponseOID();
    if (responseOID != null)
    {
      buffer.append(", responseOID='");
      buffer.append(responseOID);
      buffer.append('\'');
    }

    final String responseName = getExtendedResult().getExtendedResultName();
    if ((responseName != null) && (! responseName.equals(responseOID)))
    {
      buffer.append(", responseName='");
      buffer.append(responseName);
      buffer.append('\'');
    }

    final String matchedDN = getMatchedDN();
    if (matchedDN != null)
    {
      buffer.append(", matchedDN='");
      buffer.append(matchedDN);
      buffer.append('\'');
    }

    final String diagnosticMessage = getDiagnosticMessage();
    if (diagnosticMessage != null)
    {
      buffer.append(", diagnosticMessage='");
      buffer.append(diagnosticMessage);
      buffer.append('\'');
    }

    final String[] referralURLs = getReferralURLs();
    if (referralURLs.length > 0)
    {
      buffer.append(", referralURLs={");

      for (int i=0; i < referralURLs.length; i++)
      {
        if (i > 0)
        {
          buffer.append(", ");
        }

        buffer.append('\'');
        buffer.append(referralURLs[i]);
        buffer.append('\'');
      }

      buffer.append('}');
    }

    final Control[] responseControls = getResponseControls();
    if (responseControls.length > 0)
    {
      buffer.append(", responseControls={");

      for (int i=0; i < responseControls.length; i++)
      {
        if (i > 0)
        {
          buffer.append(", ");
        }

        buffer.append(responseControls[i]);
      }

      buffer.append('}');
    }

    buffer.append(", ldapSDKVersion=");
    buffer.append(Version.NUMERIC_VERSION_STRING);
    buffer.append(", revision=");
    buffer.append(Version.REVISION_NUMBER);
    buffer.append(')');
  }
}
