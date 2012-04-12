package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import plugins.WebOfTrust.datamodel.IEdge;
import plugins.WebOfTrust.datamodel.IVertex;

import thomasmarkus.nl.freenet.graphdb.Edge;
import thomasmarkus.nl.freenet.graphdb.H2Graph;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;

public class IdentityUpdater implements ClientGetCallback{

	private final H2Graph graph;
	private final boolean isOwnIdentity;
	private RequestScheduler rs;

	public IdentityUpdater(RequestScheduler rs, H2Graph graph, HighLevelSimpleClient hl, boolean isOwnIdentity)
	{
		this.graph = graph;
		this.isOwnIdentity = isOwnIdentity;
		this.rs = rs;
	}

	@Override
	public void onMajorProgress(ObjectContainer oc) {
		System.out.println("Something happened!");
	}

	@Override
	public void onFailure(FetchException fe, ClientGetter cg, ObjectContainer oc) {

		//deregister our request
		rs.removeInFlight(cg);

		System.out.println(fe.getMessage());		
		if (fe.mode == FetchException.PERMANENT_REDIRECT)
		{
			rs.addBacklog(fe.newURI);
		}
	}

	@Override
	public void onSuccess(FetchResult fr, ClientGetter cg, ObjectContainer oc) {

		try{
			//deregister our request
			rs.removeInFlight(cg);

			//parse the xml
			Document doc = Utils.getXMLDoc(fr);

			//try to add additional trust relations
			addTrustRelations(doc, cg.getURI());
		}
		catch(Exception e)
		{
			e.printStackTrace();	
		}
	}


	private void fetchIdentity(FreenetURI identity)
	{
		rs.addBacklog(identity);
	}

	private void addTrustRelations(Document doc, FreenetURI freenetURI)
	{
		try
		{
			Node ownIdentity = doc.getElementsByTagName("Identity").item(0);
			String identityName = ownIdentity.getAttributes().getNamedItem("Name").getNodeValue();
			String publishesTrustList = ownIdentity.getAttributes().getNamedItem("PublishesTrustList").getNodeValue();

			final String identityID = Utils.getIDFromKey(freenetURI);
			long current_edition = freenetURI.getEdition();

			//setup identiy and store it in the graphstore
			long identity = getOwnIdentity(freenetURI, current_edition);

			//always update:
			graph.updateVertexProperty(identity, IVertex.NAME, identityName);
			graph.updateVertexProperty(identity, IVertex.PUBLISHES_TRUSTLIST, publishesTrustList);
			SetContexts(identity, doc.getElementsByTagName("Context"));
			SetProperties(identity, doc.getElementsByTagName("Property"));
			
			//Add all the identities that this identity trusts in turn
			Node list = doc.getElementsByTagName("TrustList").item(0);

			if (list != null)
			{
				NodeList children = list.getChildNodes();

				//System.out.println("Number of identities that this identity trusts: " + children.getLength());
				List<Edge> trustMatches = graph.getEdgesByProperty("truster", identityID);

				for(int i=0; i < children.getLength(); i++)
				{
					Node element = children.item(i);

					if (element.getNodeType() != Node.TEXT_NODE)
					{
						try
						{
							final NamedNodeMap attr = element.getAttributes();
							final FreenetURI peerIdentityKey = new FreenetURI(attr.getNamedItem("Identity").getNodeValue());
							final String trustComment = attr.getNamedItem("Comment").getNodeValue();

							long peer = getPeerIdentity(peerIdentityKey);

							//add the trust relation, but first check whether the edge exists or not...
							String trusteeID = Utils.getIDFromKey(peerIdentityKey);

							long edge = -1;
							for(Edge candidate_edge : trustMatches)
							{
								if (candidate_edge.getProperties().get("trustee").contains(trusteeID))
								{
									edge = candidate_edge.id;
								}
							}

							if (edge == -1) //new edge
							{
								edge = graph.addEdge(identity, peer);
								graph.updateEdgeProperty(edge, "truster",identityID);
								graph.updateEdgeProperty(edge, "trustee", trusteeID);
							}

							//always update the trust 
							graph.updateEdgeProperty(edge, "comment", trustComment);

							//update the trust value/score
							graph.updateEdgeProperty(edge, IEdge.SCORE, Byte.toString(Byte.parseByte(attr.getNamedItem("Value").getNodeValue())));

							//TODO: update the trust values for all identities (actually... just the ones we would like to fetch now..)
							//ScoreComputer sc = new ScoreComputer(graph);
							//call the sc for all the identities

							//fetch the new identity if the USK value we're referred to seeing is newer than the one we are already aware of
							final long current_ref_edition = peerIdentityKey.getEdition();
							final Map<String, List<String>> peerProperties = graph.getVertexProperties(peer);
							if (peerProperties == null || !peerProperties.containsKey(IVertex.EDITION) || 
									(Long.parseLong(graph.getVertexProperties(peer).get(IVertex.EDITION).get(0)) < current_ref_edition))
							{
								graph.updateVertexProperty(peer, IVertex.EDITION, Long.toString(current_ref_edition));
								fetchIdentity(peerIdentityKey);
							}
						}
						catch(NullPointerException e)
						{
							e.printStackTrace();
							System.out.println("Oops, failed to retrieve attributes.");
						}
					}
				}
			}
		}
		catch(Exception ex)
		{
			ex.printStackTrace();
		}
	}

	private long getPeerIdentity(final FreenetURI peerIdentityKey)	throws SQLException 
	{
		List<Long> identityMatches = graph.getVertexByPropertyValue("id", Utils.getIDFromKey(peerIdentityKey));
		long peer;

		//existing identity
		if (identityMatches.size() > 0) {
			peer = identityMatches.get(0);
			graph.updateVertexProperty(peer, "lastFetched", Long.toString(System.currentTimeMillis()));								
		}
		else  {	//or create a new one
			peer = graph.createVertex();
			graph.updateVertexProperty(peer, IVertex.ID, Utils.getIDFromKey(peerIdentityKey));

			//default to edition 0, because we want to ensure fetching the identity
			graph.updateVertexProperty(peer, IVertex.EDITION, "-1");

			//store when we first saw the identity
			graph.updateVertexProperty(peer, "firstSeen", Long.toString(System.currentTimeMillis()));
		}
		
		updateKeyEditions(peerIdentityKey, peerIdentityKey.getEdition(), peer);
		return peer;
	}

	private void SetProperties(long identity, NodeList propertiesXML) throws SQLException 
	{
		//add all the (new) properties
		for(int i=0; i < propertiesXML.getLength(); i++)
		{
			Node context = propertiesXML.item(i);
			final NamedNodeMap attr = context.getAttributes();
			final String name = attr.getNamedItem("Name").getNodeValue();
			final String value = attr.getNamedItem("Value").getNodeValue();
			
			graph.updateVertexProperty(identity, name, value);
		}
	}

	private long getOwnIdentity(FreenetURI identityKey, long current_edition) throws SQLException {
		
		final String identityID = Utils.getIDFromKey(identityKey);
		final List<Long> matches = graph.getVertexByPropertyValue("id", identityID);
		long identity;

		if (matches.size() > 0) {	//existing identity
			identity = matches.get(0);
		}
		else  { //or create a new one
			identity = graph.createVertex();

			graph.updateVertexProperty(identity, IVertex.ID, identityID);
			graph.updateVertexProperty(identity, IVertex.EDITION, Long.toString(current_edition));
			
			//update the request and insert editions based on the edition
			if (isOwnIdentity) graph.updateVertexProperty(identity, "ownIdentity", Boolean.toString(true));
		}

		updateKeyEditions(identityKey, current_edition, identity);
		return identity;
	}

	private void updateKeyEditions(FreenetURI identityKey,
			long current_edition, long identity) throws SQLException {
		//update the request and insert keys with the most recent known edition
		graph.updateVertexProperty(identity, "requestURI", identityKey.setSuggestedEdition(current_edition).toASCIIString());
		Map<String, List<String>> props = graph.getVertexProperties(identity);
		if (props.containsKey(IVertex.INSERT_URI))
		{
			FreenetURI insertURI;
			try {
				insertURI = new FreenetURI(props.get(IVertex.INSERT_URI).get(0));
				insertURI.setSuggestedEdition(current_edition);
				graph.updateVertexProperty(identity, IVertex.INSERT_URI, insertURI.toASCIIString());
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
		}
	}

	private void SetContexts(long identity, NodeList contextsXML) throws SQLException 
	{
		//remove all old contexts
		graph.removeVertexProperty(identity, "contextName");
		
		//add all the (new) contexts
		for(int i=0; i < contextsXML.getLength(); i++)
		{
			Node context = contextsXML.item(i);
			final NamedNodeMap attr = context.getAttributes();
			final String name = attr.getNamedItem("Name").getNodeValue();

			graph.addVertexProperty(identity, "contextName", name);
		}
	}

}
