/*
 * Copyright 2013 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.emc.atmos.sync.plugins;

import com.emc.atmos.api.AtmosApi;
import com.emc.atmos.api.ObjectIdentifier;
import com.emc.atmos.api.bean.Metadata;
import com.emc.atmos.sync.util.AtmosUtil;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Intended to provide policy transitioning via metadata update with support for maintaining expiration/retention
 * dates. Use this plug-in in combination with AtmosSource and DummyDestination. The objects in the source cloud will
 * be modified with the assumed intent of transitioning to a different policy.
 */
public class PolicyTransitionPlugin extends SyncPlugin {
    private static final Logger l4j = Logger.getLogger( PolicyTransitionPlugin.class );

    public static final String TARGET_POLICY_OPTION = "target-policy";
    public static final String TARGET_POLICY_DESC = "The target policy in which objects should be placed. If specified, the system metadata for each object is checked to verify the object is not already in the target policy. If so, it is left untouched";
    public static final String TARGET_POLICY_ARG_NAME = "policy-name";

    public static final String META_TRIGGER_OPTION = "meta-trigger";
    public static final String META_TRIGGER_DESC = "Specifies the metadata (name=value) that will trigger the policy change";
    public static final String META_TRIGGER_ARG_NAME = "metadata";

    public static final String REMOVE_META_OPTION = "remove-meta";
    public static final String REMOVE_META_DESC = "If enabled, removes the trigger metadata after it has been set. Use this if the trigger metadata serves no other purpose than to change the policy";

    public static final String KEEP_EXPIRATION_OPTION = "keep-expiration";
    public static final String KEEP_EXPIRATION_DESC = "When a new policy is applied, the expiration date may be changed. This option will pull the expiration date before the update and attempt to re-set the expiration after the update (via user.maui.expirationEnd)";

    public static final String KEEP_RETENTION_OPTION = "keep-retention";
    public static final String KEEP_RETENTION_DESC = "Same as keep-expiration except for retention end date";

    // timed operations
    private static final String OPERATION_DISABLE_RETENTION = "AtmosDisableRetention";
    private static final String OPERATION_SET_USER_META = "AtmosSetUserMeta";
    private static final String OPERATION_DELETE_USER_META = "AtmosDeleteUserMeta";
    private static final String OPERATION_SET_RETENTION_EXPIRATION = "AtmosSetRetentionExpiration";
    private static final String OPERATION_TOTAL = "TotalTime";

    private String targetPolicy;
    private Metadata triggerMetadata;
    private boolean removeMeta;
    private boolean keepExpiration;
    private boolean keepRetention;
    private AtmosSource source;

    @Override
    public void filter( final SyncObject obj ) {
        SourceAtmosId idAnn = (SourceAtmosId) obj.getAnnotation( SourceAtmosId.class );
        final ObjectIdentifier id = (idAnn.getId() != null) ? idAnn.getId() : idAnn.getPath();

        // ignore the object if it's already in the target policy
        if ( targetPolicy != null ) {
            Metadata policyMeta = obj.getMetadata().getSystemMetadata().get( "policyname" );
            if ( policyMeta != null && targetPolicy.equals( policyMeta.getValue() ) ) {
                l4j.debug( "Object " + id + " is already in target policy " + targetPolicy + "; ignoring" );
                return;
            }
        }

        final AtmosApi atmosApi = source.getAtmos();
        timeOperationStart( OPERATION_TOTAL );
        try {
            obj.getMetadata(); // this will lazy-load metadata and object-info

            // check if the object is already in retention. if so, we'll need to disable retention before we can change
            // the metadata
            if ( obj.getMetadata().isRetentionEnabled() ) {
                time( new Timeable<Void>() {
                    @Override
                    public Void call() {
                        atmosApi.setUserMetadata( id, new Metadata( "user.maui.retentionEnable", "false", false ) );
                        return null;
                    }
                }, OPERATION_DISABLE_RETENTION );
            }

            // change policy (update metadata)
            time( new Timeable<Void>() {
                @Override
                public Void call() {
                    atmosApi.setUserMetadata( id, triggerMetadata );
                    return null;
                }
            }, OPERATION_SET_USER_META );

            // remove metadata after transition
            // TODO: figure out why this doesn't work when transitioning to a retention policy
            if ( removeMeta ) {
                time( new Timeable<Object>() {
                    @Override
                    public Object call() {
                        atmosApi.deleteUserMetadata( id, triggerMetadata.getName() );
                        return null;
                    }
                }, OPERATION_DELETE_USER_META );
            }

            // if keeping retention or deletion, re-set those to the old values
            final List<Metadata> retExpList = new ArrayList<Metadata>();

            if ( keepExpiration )
                retExpList.addAll( AtmosUtil.getExpirationMetadataForUpdate( obj.getMetadata() ) );

            if ( keepRetention )
                retExpList.addAll( AtmosUtil.getRetentionMetadataForUpdate( obj.getMetadata() ) );

            if ( retExpList.size() > 0 ) {
                time( new Timeable<Void>() {
                    @Override
                    public Void call() {
                        atmosApi.setUserMetadata( id, retExpList.toArray( new Metadata[retExpList.size()] ) );
                        return null;
                    }
                }, OPERATION_SET_RETENTION_EXPIRATION );
            }

            getNext().filter( obj );

            timeOperationComplete( OPERATION_TOTAL );
        } catch ( RuntimeException e ) {
            timeOperationFailed( OPERATION_TOTAL );
            throw e;
        }
    }

    @SuppressWarnings( "static-access" )
    @Override
    public Options getOptions() {
        Options opts = new Options();

        opts.addOption( OptionBuilder.withDescription( TARGET_POLICY_DESC )
                                     .withLongOpt( TARGET_POLICY_OPTION )
                                     .hasArg().withArgName( TARGET_POLICY_ARG_NAME ).create() );
        opts.addOption( OptionBuilder.withDescription( META_TRIGGER_DESC )
                                     .withLongOpt( META_TRIGGER_OPTION )
                                     .hasArg().withArgName( META_TRIGGER_ARG_NAME ).create() );
        opts.addOption( OptionBuilder.withDescription( REMOVE_META_DESC )
                                     .withLongOpt( REMOVE_META_OPTION ).create() );
        opts.addOption( OptionBuilder.withDescription( KEEP_EXPIRATION_DESC )
                                     .withLongOpt( KEEP_EXPIRATION_OPTION ).create() );
        opts.addOption( OptionBuilder.withDescription( KEEP_RETENTION_DESC )
                                     .withLongOpt( KEEP_RETENTION_OPTION ).create() );

        return opts;
    }

    @Override
    public boolean parseOptions( CommandLine line ) {
        if ( !line.hasOption( META_TRIGGER_OPTION ) )
            return false;

        String metaString = line.getOptionValue( META_TRIGGER_OPTION );
        if ( metaString == null )
            throw new IllegalArgumentException( "policy transition requires a metadata trigger" );

        String[] parts = metaString.split( "=" );
        if ( parts.length != 2 )
            throw new IllegalArgumentException( "metadata trigger must be specified by \"name=value\"" );

        targetPolicy = line.getOptionValue( TARGET_POLICY_OPTION );

        triggerMetadata = new Metadata( parts[0], parts[1], false );

        removeMeta = line.hasOption( REMOVE_META_OPTION );

        keepExpiration = line.hasOption( KEEP_EXPIRATION_OPTION );
        keepRetention = line.hasOption( KEEP_RETENTION_OPTION );

        return true;
    }

    @Override
    public void validateChain( SyncPlugin first ) {
        if ( triggerMetadata == null )
            throw new UnsupportedOperationException( "policy transition requires a metadata trigger" );

        if ( first instanceof AtmosSource && first.getNext() == this
             && first.getNext().getNext() instanceof DummyDestination ) {
            if ( (keepExpiration || keepRetention) && !((AtmosSource) first).isIncludeRetentionExpiration() )
                throw new UnsupportedOperationException(
                        "you must enable includeRetentionExpiration on AtmosSource to keep retention or expiration dates" );
            source = ((AtmosSource) first);
        } else {
            throw new UnsupportedOperationException(
                    "PolicyTransitionPlugin must be used solely with AtmosSource and DummyDestination" );
        }
    }

    @Override
    public String getName() {
        return "Policy Transition Plugin";
    }

    @Override
    public String getDocumentation() {
        return "The PolicyTransition plugin transitions objects to a different policy via metadata update with " +
               "support for maintaining expiration/retention dates. Use this plug-in in combination with AtmosSource " +
               "and DummyDestination. The objects in the source cloud will be modified with the assumed intent of " +
               "transitioning to a different policy.";
    }

    public String getTargetPolicy() {
        return targetPolicy;
    }

    /**
     * The target policy in which objects should be placed. If specified, the system metadata for each object is
     * checked to verify the object is not already in the target policy. If so, it is left untouched.
     */
    public void setTargetPolicy( String targetPolicy ) {
        this.targetPolicy = targetPolicy;
    }

    public Metadata getTriggerMetadata() {
        return triggerMetadata;
    }

    /**
     * Specifies the metadata that will trigger the policy change. Required.
     */
    public void setTriggerMetadata( Metadata triggerMetadata ) {
        this.triggerMetadata = triggerMetadata;
    }

    public boolean isRemoveMeta() {
        return removeMeta;
    }

    /**
     * If true, removes the trigger metadata after it has been set. Use this if the trigger metadata serves no other
     * purpose than to change the policy. Default is false.
     */
    public void setRemoveMeta( boolean removeMeta ) {
        this.removeMeta = removeMeta;
    }

    public boolean isKeepExpiration() {
        return keepExpiration;
    }

    /**
     * When a new policy is applied, the expiration date may be changed. When set to true, the expiration date is
     * pulled before the update and an attempt is made to re-set the expiration after the update (via
     * user.maui.expirationEnd). Default is false.
     */
    public void setKeepExpiration( boolean keepExpiration ) {
        this.keepExpiration = keepExpiration;
    }

    public boolean isKeepRetention() {
        return keepRetention;
    }

    /**
     * Same as keepExpiration except for retention end date
     */
    public void setKeepRetention( boolean keepRetention ) {
        this.keepRetention = keepRetention;
    }
}
