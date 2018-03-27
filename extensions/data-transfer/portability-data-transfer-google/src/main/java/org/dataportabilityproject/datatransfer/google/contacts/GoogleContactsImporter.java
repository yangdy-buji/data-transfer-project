/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dataportabilityproject.datatransfer.google.contacts;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.people.v1.PeopleService;
import com.google.api.services.people.v1.model.EmailAddress;
import com.google.api.services.people.v1.model.FieldMetadata;
import com.google.api.services.people.v1.model.Name;
import com.google.api.services.people.v1.model.Person;
import com.google.api.services.people.v1.model.PhoneNumber;
import com.google.api.services.people.v1.model.Source;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import ezvcard.VCard;
import ezvcard.io.json.JCardReader;
import ezvcard.property.Email;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import org.dataportabilityproject.datatransfer.google.common.GoogleStaticObjects;
import org.dataportabilityproject.spi.transfer.provider.ImportResult;
import org.dataportabilityproject.spi.transfer.provider.Importer;
import org.dataportabilityproject.types.transfer.auth.TokensAndUrlAuthData;
import org.dataportabilityproject.types.transfer.models.contacts.ContactsModelWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.dataportabilityproject.datatransfer.google.common.GoogleStaticObjects.CONTACT_SOURCE_TYPE;
import static org.dataportabilityproject.datatransfer.google.common.GoogleStaticObjects.SOURCE_PARAM_NAME_TYPE;
import static org.dataportabilityproject.datatransfer.google.common.GoogleStaticObjects.VCARD_PRIMARY_PREF;

public class GoogleContactsImporter implements Importer<TokensAndUrlAuthData, ContactsModelWrapper> {

  private static final FieldMetadata PRIMARY_FIELD_METADATA = new FieldMetadata().setPrimary(true);
  private static final FieldMetadata SECONDARY_FIELD_METADATA =
      new FieldMetadata().setPrimary(false);

  private static final Logger logger = LoggerFactory.getLogger(GoogleContactsExporter.class);
  private volatile PeopleService peopleService;

  public GoogleContactsImporter() {
    this.peopleService = null;
  }

  @VisibleForTesting
  GoogleContactsImporter(PeopleService peopleService) {
    this.peopleService = peopleService;
  }

  // TODO(olsona): can we guarantee that <VCARDPROPERTY>.getPref() will always return a value?
  @VisibleForTesting
  static Person convert(VCard vCard) {
    Person person = new Person();

    Preconditions.checkArgument(atLeastOneNamePresent(vCard), "At least one name must be present");
    person.setNames(Collections.singletonList(getPrimaryGoogleName(vCard.getStructuredNames())));
    // TODO(olsona): nicknames for other source-typed names?
    // TODO(olsona): can we *actually* add more than one name?
    // No two names from the same source can be uploaded, and only names with source type CONTACT
    // can be uploaded, but what about names with source type null?

    if (vCard.getAddresses() != null) {
      person.setAddresses(
          vCard
              .getAddresses()
              .stream()
              .map(GoogleContactsImporter::convertToGoogleAddress)
              .collect(Collectors.toList()));
    }

    if (vCard.getTelephoneNumbers() != null) {
      person.setPhoneNumbers(
          vCard
              .getTelephoneNumbers()
              .stream()
              .map(GoogleContactsImporter::convertToGooglePhoneNumber)
              .collect(Collectors.toList()));
    }

    if (vCard.getEmails() != null) {
      person.setEmailAddresses(
          vCard
              .getEmails()
              .stream()
              .map(GoogleContactsImporter::convertToGoogleEmail)
              .collect(Collectors.toList()));
    }

    return person;
  }

  private static Name convertToGoogleName(StructuredName vCardName) {
    Name name = new Name();
    name.setFamilyName(vCardName.getFamily());
    name.setGivenName(vCardName.getGiven());

    FieldMetadata fieldMetadata = new FieldMetadata();
    boolean isPrimary = (vCardName.getAltId() == null);
    fieldMetadata.setPrimary(isPrimary);

    String vCardNameSource = vCardName.getParameter(SOURCE_PARAM_NAME_TYPE);
    if (CONTACT_SOURCE_TYPE.equals(vCardNameSource)) {
      Source source = new Source().setType(vCardNameSource);
      fieldMetadata.setSource(source);
    }

    name.setMetadata(fieldMetadata);
    // TODO(olsona): address formatting, structure, phonetics, suffixes, prefixes

    return name;
  }

  private static Name getPrimaryGoogleName(List<StructuredName> vCardNameList) {
    StructuredName primaryVCardName;

    // first look if there's a primary name (or names)
    // if no primary name exists, simply pick the first "alt" name
    List<StructuredName> primaryNames =
        vCardNameList.stream().filter(a -> a.getAltId() == null).collect(Collectors.toList());
    if (primaryNames.size() > 0) {
      primaryVCardName = primaryNames.get(0);
    } else {
      primaryVCardName = vCardNameList.get(0);
    }

    return convertToGoogleName(primaryVCardName);
  }

  private static com.google.api.services.people.v1.model.Address convertToGoogleAddress(
      ezvcard.property.Address vCardAddress) {
    com.google.api.services.people.v1.model.Address personAddress =
        new com.google.api.services.people.v1.model.Address();

    personAddress.setCountry(vCardAddress.getCountry());
    personAddress.setRegion(vCardAddress.getRegion());
    personAddress.setCity(vCardAddress.getLocality());
    personAddress.setPostalCode(vCardAddress.getPostalCode());
    personAddress.setStreetAddress(vCardAddress.getStreetAddress());
    personAddress.setPoBox(vCardAddress.getPoBox());
    personAddress.setExtendedAddress(vCardAddress.getExtendedAddress());

    // check to guard against unboxing of a null value
    personAddress.setMetadata(
        vCardAddress.getPref() != null && vCardAddress.getPref() == VCARD_PRIMARY_PREF
            ? PRIMARY_FIELD_METADATA
            : SECONDARY_FIELD_METADATA);

    return personAddress;
  }

  private static PhoneNumber convertToGooglePhoneNumber(Telephone vCardTelephone) {
    PhoneNumber phoneNumber = new PhoneNumber();
    phoneNumber.setValue(vCardTelephone.getText());
    if (vCardTelephone.getPref() != null && vCardTelephone.getPref() == VCARD_PRIMARY_PREF) {
      phoneNumber.setMetadata(PRIMARY_FIELD_METADATA);
    } else {
      phoneNumber.setMetadata(SECONDARY_FIELD_METADATA);
    }

    return phoneNumber;
  }

  private static EmailAddress convertToGoogleEmail(Email vCardEmail) {
    EmailAddress emailAddress = new EmailAddress();
    emailAddress.setValue(vCardEmail.getValue());
    if (vCardEmail.getPref() != null && vCardEmail.getPref() == VCARD_PRIMARY_PREF) {
      emailAddress.setMetadata(PRIMARY_FIELD_METADATA);
    } else {
      emailAddress.setMetadata(SECONDARY_FIELD_METADATA);
    }
    // TODO(olsona): address display name, formatted type, etc

    return emailAddress;
  }

  private static boolean atLeastOneNamePresent(VCard vCard) {
    // TODO(olsona): there are more checks we could make
    return vCard.getStructuredNames().size() >= 1 && vCard.getStructuredName() != null;
  }

  @Override
  public ImportResult importItem(UUID jobId, TokensAndUrlAuthData authData, ContactsModelWrapper data) {
    JCardReader reader = new JCardReader(data.getVCards());
    try {
      // TODO(olsona): address any other problems that might arise in conversion
      List<VCard> vCardList = reader.readAll();
      for (VCard vCard : vCardList) {
        Person person = convert(vCard);
        getOrCreatePeopleService(authData).people().createContact(person).execute();
      }
      return ImportResult.OK;
    } catch (IOException e) {
      return new ImportResult(ImportResult.ResultType.ERROR, e.getMessage());
    }
  }

  private PeopleService getOrCreatePeopleService(TokensAndUrlAuthData authData) {
    return peopleService == null ? makePeopleService(authData) : peopleService;
  }

  private synchronized PeopleService makePeopleService(TokensAndUrlAuthData authData) {
    Credential credential= new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(authData.getAccessToken());;
    return new PeopleService.Builder(
            GoogleStaticObjects.getHttpTransport(), GoogleStaticObjects.JSON_FACTORY, credential)
        .setApplicationName(GoogleStaticObjects.APP_NAME)
        .build();
  }
}