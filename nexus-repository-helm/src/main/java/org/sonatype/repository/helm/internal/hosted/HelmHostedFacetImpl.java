/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2018-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.repository.helm.internal.hosted;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.storage.TempBlob;
import org.sonatype.nexus.repository.transaction.TransactionalDeleteBlob;
import org.sonatype.nexus.repository.transaction.TransactionalStoreBlob;
import org.sonatype.nexus.repository.transaction.TransactionalTouchBlob;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.transaction.UnitOfWork;
import org.sonatype.repository.helm.HelmAttributes;
import org.sonatype.repository.helm.HelmFacet;
import org.sonatype.repository.helm.internal.AssetKind;
import org.sonatype.repository.helm.internal.util.HelmAttributeParser;
import org.sonatype.repository.helm.internal.util.HelmDataAccess;
import org.sonatype.repository.helm.internal.util.HelmPathUtils;

import org.apache.commons.lang3.StringUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.repository.helm.internal.util.HelmDataAccess.HASH_ALGORITHMS;

/**
 * {@link HelmHostedFacetImpl implementation}
 *
 * @since 0.0.2
 */
@Named
public class HelmHostedFacetImpl
    extends FacetSupport
    implements HelmHostedFacet
{
  private final HelmDataAccess helmDataAccess;

  private final HelmAttributeParser helmAttributeParser;

  private HelmFacet helmFacet;

  @Inject
  public HelmHostedFacetImpl(
      final HelmDataAccess helmDataAccess,
      final HelmAttributeParser helmAttributeParser)
  {
    this.helmDataAccess = helmDataAccess;
    this.helmAttributeParser = helmAttributeParser;
  }

  @Override
  protected void doInit(final Configuration configuration) throws Exception {
    super.doInit(configuration);
    getRepository().facet(StorageFacet.class).registerWritePolicySelector(new HelmHostedWritePolicySelector());
    helmFacet = facet(HelmFacet.class);
  }

  @Nullable
  @Override
  @TransactionalTouchBlob
  public Content get(final String path) {
    checkNotNull(path);
    StorageTx tx = UnitOfWork.currentTx();

    Asset asset = helmDataAccess.findAsset(tx, tx.findBucket(getRepository()), path);
    if (asset == null) {
      return null;
    }
    if (asset.markAsDownloaded()) {
      tx.saveAsset(asset);
    }

    return helmDataAccess.toContent(asset, tx.requireBlob(asset.requireBlobRef()));
  }

  @Override
  @TransactionalStoreBlob
  public Asset upload(final Payload payload, final AssetKind assetKind) throws IOException {
    try (TempBlob tempBlob = facet(StorageFacet.class).createTempBlob(payload, HASH_ALGORITHMS)) {
      HelmAttributes attributes = helmAttributeParser.getHelmAttributes(tempBlob.get(), assetKind);
      String name = attributes.getName();
      String version = attributes.getVersion();

      if (StringUtils.isBlank(name)) {
        throw new ValidationErrorsException("Metadata is missing the name attribute");
      }

      if (StringUtils.isBlank(version)) {
        throw new ValidationErrorsException("Metadata is missing the version attribute");
      }

      String path = HelmPathUtils.buildPath(name, version, assetKind);

      StorageTx tx = UnitOfWork.currentTx();
      Bucket bucket = tx.findBucket(getRepository());
      final Asset asset = helmFacet.findOrCreateAssetWithComponent(path, assetKind, tx, bucket, attributes);
      helmDataAccess.saveAsset(tx, asset, tempBlob, payload);
      return asset;
    }
  }

  @Override
  @TransactionalDeleteBlob
  public boolean delete(final String path) {
    checkNotNull(path);

    StorageTx tx = UnitOfWork.currentTx();
    Bucket bucket = tx.findBucket(getRepository());

    Asset asset = helmDataAccess.findAsset(tx, bucket, path);
    if (asset == null) {
      return false;
    } else {
      tx.deleteAsset(asset);
      return true;
    }
  }
}
