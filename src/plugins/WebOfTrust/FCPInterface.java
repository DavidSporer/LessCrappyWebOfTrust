package plugins.WebOfTrust;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import plugins.WebOfTrust.datamodel.IEdge;
import plugins.WebOfTrust.datamodel.IVertex;

import thomasmarkus.nl.freenet.graphdb.Edge;
import thomasmarkus.nl.freenet.graphdb.H2Graph;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class FCPInterface {

	private H2Graph graph;

	public FCPInterface(H2Graph graph)
	{
		this.graph = graph;
	}

	public void handle(PluginReplySender prs, SimpleFieldSet sfs, Bucket bucket, int accessType) throws SQLException {
		System.out.println("Received the following message type: " + sfs.get("Message") + " with identifier: " + prs.getIdentifier());

		try {
			SimpleFieldSet sfsReply = new SimpleFieldSet(true);

			if (sfs.get("Message").equals("Error"))
			{
				System.out.println(sfs.get("ErrorMessage"));
			}

			else if (sfs.get("Message").equals("Ping"))
			{
				sfsReply.putSingle("Message", "Pong");
			}
			else if (sfs.get("Message").equals("GetOwnIdentities"))
			{
				sfsReply.putSingle("Message", "OwnIdentities");
				List<Long> ownIdentities = graph.getVertexByPropertyValue(IVertex.OWN_IDENTITY, "true");
				for(int i=0; i < ownIdentities.size(); i++)
				{
					Map<String, List<String>> identityProperties = graph.getVertexProperties(ownIdentities.get(i));

					sfsReply.putOverwrite("Identity" + i, identityProperties.get(IVertex.ID).get(0));
					sfsReply.putOverwrite("RequestURI" + i, identityProperties.get(IVertex.REQUEST_URI).get(0));
					sfsReply.putOverwrite("InsertURI" + i, identityProperties.get("insertURI").get(0));
					sfsReply.putOverwrite("Nickname" + i, identityProperties.get(IVertex.NAME).get(0));

					int contextCounter = 0;
					for (String context : identityProperties.get("contextName")) {
						sfsReply.putOverwrite("Contexts" + i + ".Context" + contextCounter++, context);
					}

					//TODO: only include properties that aren't one of the above
					int propertiesCounter = 0;
					for (Entry<String, List<String>> property : identityProperties.entrySet()) {
						sfsReply.putOverwrite("Properties" + i + ".Property" + propertiesCounter + ".Name", property.getKey());
						sfsReply.putOverwrite("Properties" + i + ".Property" + propertiesCounter++ + ".Value", property.getValue().get(0));
					}
				}
				System.out.println("Generated message containing the own Identities");
			}
			else if (sfs.get("Message").equals("AddContext"))
			{
				final String identityID = sfs.get("Identity");
				final String context = sfs.get("Context");

				List<Long> identity_vertex_ids = graph.getVertexByPropertyValue(IVertex.ID, identityID);
				for(long identity_vertex_id : identity_vertex_ids)
				{
					graph.addVertexProperty(identity_vertex_id, "contextName", context);
				}

				sfsReply.putOverwrite("Message", "ContextAdded");
			}
			else if (sfs.get("Message").equals("RemoveContext"))
			{
				final String identityID = sfs.get("Identity");
				final String context = sfs.get("Context");

				List<Long> identity_vertex_ids = graph.getVertexByPropertyValue(IVertex.ID, identityID);
				for(long identity_vertex_id : identity_vertex_ids)
				{
					graph.removeVertexPropertyValue(identity_vertex_id, "contextName", context);
				}

				sfsReply.putOverwrite("Message", "ContextRemoved");
			}
			else if (sfs.get("Message").equals("GetIdentitiesByScore"))
			{
				String trusterID = sfs.get("Truster");
				final String selection = sfs.get("Selection").trim();
				final String context = sfs.get("Context");
				final boolean includeTrustValue = sfs.getBoolean("WantTrustValues", false);

				int select = 0;
				if (selection.equals("+")) select = 0;
				else if (selection.equals("0")) select = -1;

				//find `random' ownIdentity if none is specified
				if (trusterID == null || trusterID.equals("null")) //TODO: this requires updates below... we should get the identities trusted by ANY ownIdentity
				{
					List<Long> vertices = graph.getVertexByPropertyValue(IVertex.OWN_IDENTITY, "true");
					for(long vertex : vertices)
					{
						Map<String, List<String>> props = graph.getVertexProperties(vertex);
						trusterID = props.get("id").get(0);
					}
				}
				
				List<Long> vertices = graph.getVerticesWithPropertyValueLargerThan(IVertex.TRUST+"."+trusterID, select);
				sfsReply.putSingle("Message", "Identities");
				int i = 0;
				for(int vertex_index=0; vertex_index < vertices.size(); vertex_index++)
				{
					Map<String, List<String>> properties = graph.getVertexProperties(vertices.get(vertex_index));
					
					//check whether the identity has the context we need
					//TODO: This should be done as part of the query
					if (properties.containsKey("contextName") && properties.get("contextName").contains(context))
					{
						sfsReply.putOverwrite("Identity" + i, properties.get(IVertex.ID).get(0));
						sfsReply.putOverwrite("RequestURI" + i, properties.get(IVertex.REQUEST_URI).get(0));
						sfsReply.putOverwrite("Nickname" + i, properties.get(IVertex.NAME).get(0));

						int contextCounter = 0;
						for (String identityContext: properties.get("contextName")) {
							sfsReply.putOverwrite("Contexts" + i + ".Context" + contextCounter++, identityContext);
						}

						int propertiesCounter = 0;
						for (Entry<String, List<String>> property : properties.entrySet()) {
							sfsReply.putOverwrite("Properties" + i + ".Property" + propertiesCounter + ".Name", property.getKey());
							sfsReply.putOverwrite("Properties" + i + ".Property" + propertiesCounter++ + ".Value", property.getValue().get(0));
						}

						sfs.putOverwrite("ScoreOwner" + i, properties.get("id").get(0));

						//TODO: this is the actual score assigned by the trusterID, I think? not sure...
						sfsReply.putOverwrite("Score" + i, properties.get("score."+trusterID).get(0));
						sfs.putOverwrite("Rank" + i, properties.get("score."+trusterID).get(0)); //TODO: rank isn't stored yet by score computation
						if (includeTrustValue)
						{
							sfsReply.putOverwrite("Trust" + i, properties.get(IVertex.TRUST+"."+trusterID).get(0));	
						}
						i += 1;
					}
				}
			}
			else if (sfs.get("Message").equals("GetIdentity"))
			{
				final String trusterID = sfs.get("Truster"); 
				final String identityID = sfs.get("Identity");

				sfsReply.putOverwrite("Message", "Identity");

				long own_id = graph.getVertexByPropertyValue(IVertex.ID, trusterID).get(0);
				
				List<Long> identities = graph.getVertexByPropertyValue(IVertex.ID, identityID);

					for(long identity : identities)
					{
						Map<String, List<String>> props = graph.getVertexProperties(identity);

						sfsReply.putOverwrite("Nickname", props.get(IVertex.NAME).get(0));
						sfsReply.putOverwrite("RequestURI", props.get(IVertex.REQUEST_URI).get(0));

						try	//directly trusted
						{
							long edge = graph.getEdgeByVerticesAndProperty(own_id, identity, IEdge.SCORE);
							Map<String, List<String>> edge_props = graph.getEdeProperties(edge);

							sfsReply.putOverwrite("Score", edge_props.get(plugins.WebOfTrust.datamodel.IEdge.SCORE).get(0));
						}
						catch(SQLException e) //not directly trusted, so set score accordingly
						{
							sfsReply.putOverwrite("Score", "null");
						}
						
						sfsReply.putOverwrite("Trust", props.get(IVertex.TRUST+"."+trusterID).get(0));

						//determine whether we trust the identity directly with the trusterID?
						if (isDirectlyTrusted(own_id, identity)) sfsReply.putOverwrite("Rank", "1");
						else sfsReply.putOverwrite("Rank", "2");

						int i=0;
						for(String context : props.get("contextName"))
						{
							sfsReply.putOverwrite("Context" + i, context);
							i += 1;
						}

						int propertiesCounter = 0;
						for (Entry<String, List<String>> property : props.entrySet()) {
							sfsReply.putOverwrite("Property" + propertiesCounter + ".Name", property.getKey());
							sfsReply.putOverwrite("Property" + propertiesCounter++ + ".Value", property.getValue().get(0));
						}
					}
			}
			else if (sfs.get("Message").equals("GetProperty"))
			{
		    	final String identityID = sfs.get("Identity");
		        final String propertyName = sfs.get("Property");
				
				List<Long> vertices = graph.getVertexByPropertyValue("id", identityID);
				Map<java.lang.String, List<java.lang.String>> props = graph.getVertexProperties(vertices.get(0));

				sfsReply.putSingle("Message", "PropertyValue");
				sfsReply.putSingle("Property", props.get(propertyName).get(0));
			}
			else if (sfs.get("Message").equals("SetProperty"))
			{
		    	final String identityID = sfs.get("Identity");
		        final String propertyName = sfs.get("Property");
		        final String propertyValue = sfs.get("Value");

				List<Long> vertices = graph.getVertexByPropertyValue("id", identityID);
				for(long vertex_id : vertices)
				{
					graph.updateVertexProperty(vertex_id, propertyName, propertyValue);
				}

		        sfsReply.putOverwrite("Message", "PropertyAdded");
			}
			else if (sfs.get("Message").equals("RemoveProperty"))
			{
		    	final String identityID = sfs.get("Identity");
		        final String propertyName = sfs.get("Property");

				List<Long> vertices = graph.getVertexByPropertyValue("id", identityID);
				for(long vertex_id : vertices)
				{
					graph.removeVertexProperty(vertex_id, propertyName);
				}

				sfsReply.putOverwrite("Message", "PropertyRemoved");
			}
			else if (sfs.get("Message").equals("SetTrust"))
			{
		    	final String trusterID = getMandatoryParameter(sfs, "Truster");
		    	final String trusteeID = getMandatoryParameter(sfs, "Trustee");
		    	final String trustValue = getMandatoryParameter(sfs, "Value");
		    	final String trustComment = getMandatoryParameter(sfs, "Comment");

		    	long truster = graph.getVertexByPropertyValue("id", trusterID).get(0);
		    	long trustee = graph.getVertexByPropertyValue("id", trusteeID).get(0);
		    	
		    	long edge;
		    	try
		    	{
		    		edge = graph.getEdgeByVerticesAndProperty(truster, trustee, "score");	
		    	}
		    	catch(SQLException e) //edge doesn't exist
		    	{
		    		edge = graph.addEdge(truster, trustee);
		    	}

		    	graph.updateEdgeProperty(edge, IEdge.SCORE, trustValue);
		    	graph.updateEdgeProperty(edge, IEdge.COMMENT, trustComment);
		    	
		    	sfsReply.putOverwrite("Message", "TrustSet");
				sfsReply.putOverwrite("Truster", trusterID);
				sfsReply.putOverwrite("Trustee", trusteeID);
				sfsReply.putOverwrite("Value", trustValue);
				sfsReply.putOverwrite("Comment", trustComment);
			}
			else if (sfs.get("Message").equals("RemoveTrust"))
			{
				final String trusterID = getMandatoryParameter(sfs, "Truster");
				final String trusteeID = getMandatoryParameter(sfs, "Trustee");

		    	long truster = graph.getVertexByPropertyValue(IVertex.ID, trusterID).get(0);
		    	long trustee = graph.getVertexByPropertyValue(IVertex.ID, trusteeID).get(0);
	    		try
	    		{
	    			long edge = graph.getEdgeByVerticesAndProperty(truster, trustee, IEdge.SCORE);	
	    			graph.removeEdge(edge);
	    		}
		    	catch(SQLException e) 
		    	{
		    		System.out.println("Failed to find edge with vertex_from: " + truster + " vertex_to: " + trustee + "and the 'score' property");
		    	}
				
				sfsReply.putOverwrite("Message", "TrustRemoved");
				sfsReply.putOverwrite("Truster", trusterID);
				sfsReply.putOverwrite("Trustee", trusteeID);
			}
			else
			{
				System.out.println("Failed to match message: " + sfs.get("Message") + " with reply");
				sfsReply.putSingle("Message", "Error");
				sfsReply.putSingle("Description", "Could not match message with reply");
			}

			System.out.println("Sending message: " + sfsReply.get("Message"));
			
			//send the actual message
			prs.send(sfsReply);
		}
		catch (PluginNotFoundException e) {
			e.printStackTrace();
		}
	}

    private String getMandatoryParameter(final SimpleFieldSet sfs, final String name) throws IllegalArgumentException {
    	final String result = sfs.get(name);
    	if(result == null)
    		throw new IllegalArgumentException("Missing mandatory parameter: " + name);
    	
    	return result;
    }

    private boolean isDirectlyTrusted(long own_id, long identity) throws SQLException
    {
		for(Edge edge : graph.getIncomingEdges(identity))
		{
			if (edge.vertex_from == own_id) return true;
		}
		return false;
    }
    
}