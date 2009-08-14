/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Library.search;

import plugins.Library.Library;
import plugins.Library.index.Request;
import plugins.Library.index.AbstractRequest;
import plugins.Library.serial.ProgressParts;
import plugins.Library.serial.TaskAbortException;

import freenet.support.Logger;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import plugins.Library.index.CompositeRequest;
import plugins.Library.index.TermEntry;
import plugins.Library.search.ResultSet.ResultOperation;
import plugins.Library.serial.CompositeProgress;
import plugins.Library.serial.Progress;

/**
 * Performs asynchronous searches over many index or with many terms and search logic
 * TODO review documentation
 * @author MikeB
 */
public class Search extends AbstractRequest<Set<TermEntry>>
				implements CompositeRequest<Set<TermEntry>> {

	private static Library library;

	private ResultOperation resultOperation;

	private ArrayList<Request<Set<TermEntry>>> subsearches;

	private String query;
	private String indexURI;

	protected int stage=0;
	protected int stageCount;

	private static HashMap<String, Search> allsearches = new HashMap<String, Search>();
	private static HashMap<Integer,Search> searchhashes = new HashMap<Integer, Search>();

	private enum SearchStatus { Unstarted, Busy, Ready, Combining, Done };
	private SearchStatus status = SearchStatus.Unstarted;


	private static void storeSearch(Search search){
		allsearches.put(search.getSubject(), search);
		searchhashes.put(search.hashCode(), search);
	}

	/**
	 * Creates a search for any number of indices, starts and returns the associated Request object
	 * TODO startSearch with array of indexes
	 *
	 * @param search string to be searched
	 * @param indexuri URI of index(s) to be used
	 * @throws InvalidSearchException if any part of the search is invalid
	 */
	public static Search startSearch(String search, String indexuri) throws InvalidSearchException, TaskAbortException{
		search = search.toLowerCase(Locale.US).trim();
		if(search.length()==0)
			throw new InvalidSearchException("Blank search");

		// See if the same search exists
		if (hasSearch(search, indexuri))
			return getSearch(search, indexuri);

		Logger.minor(Search.class, "Starting new search for "+search+" in "+indexuri);

		String[] indices = indexuri.split("[ ;]");
		if(indices.length<1 || search.trim().length()<1)
			throw new InvalidSearchException("Attempt to start search with no index or terms");
		else if(indices.length==1)
			return splitQuery(search, indexuri);
		else{
			// create search for multiple terms over multiple indices
			ArrayList<Request> indexrequests = new ArrayList(indices.length);
			for (String index : indices)
				indexrequests.add(startSearch(search, index));
			return new Search(search, indexuri, indexrequests, ResultOperation.DIFFERENTINDEXES);
		}
	}


	/**
	 * Creates Search instance depending on the given requests
	 *
	 * @param query the query this instance is being used for, only for reference
	 * @param indexURI the index uri this search is made on, only for reference
	 * @param requests subRequests of this search
	 * @param resultOperation Which set operation to do on the results of the subrequests
	 * @throws InvalidSearchException if the search is invalid
	 **/
	private Search(String query, String indexURI, List<Request> requests, ResultOperation resultOperation)
	throws InvalidSearchException{
		super(makeString(query, indexURI));
		if(resultOperation==ResultOperation.SINGLE && requests.size()!=1)
			throw new InvalidSearchException(requests.size() + " requests supplied with SINGLE operation");
		if(resultOperation==ResultOperation.REMOVE && requests.size()!=2)
			throw new InvalidSearchException("Negative operations can only have 2 parameters");
		if(		(	resultOperation==ResultOperation.PHRASE
					|| resultOperation == ResultOperation.INTERSECTION
					|| resultOperation == ResultOperation.UNION
					|| resultOperation == ResultOperation.DIFFERENTINDEXES )
				&& requests.size()<2)
			throw new InvalidSearchException(resultOperation.toString() + " operations need more than one term");
		
		query = query.toLowerCase(Locale.US).trim();
		subsearches = new ArrayList();
		for (Request request : requests) {
			if(request != null)
				subsearches.add(request);
			else
				throw new NullPointerException("Search cannot encapsulate null");
		}

		this.query = query;
		this.indexURI = indexURI;
		this.resultOperation = resultOperation;
		this.status = SearchStatus.Busy;

		storeSearch(this);
		Logger.minor(this, "Created Search object for with subRequests :"+subsearches);
	}

	/**
	 * Encapsulate a request as a Search, only so original query and uri can be stored
	 *
	 * @param query the query this instance is being used for, only for reference
	 * @param indexURI the index uri this search is made on, only for reference
	 * @param request Request to encapsulate
	 */
	private Search(String query, String indexURI, Request request){
		super(makeString(query, indexURI));
		if(request == null)
			throw new NullPointerException("Search cannot encapsulate null (query=\""+query+"\" indexURI=\""+indexURI+"\")");
		query = query.toLowerCase(Locale.US).trim();
		subsearches = new ArrayList();
		subsearches.add(request);

		this.query = query;
		this.indexURI = indexURI;
		this.resultOperation = ResultOperation.SINGLE;
		storeSearch(this);
	}


	/**
	 * Splits query into multiple searches, will be used for advanced queries
	 * @param query search query, can use various different search conventions
	 * @param indexuri uri for one index
	 * @return Set of subsearches or null if theres only one search
	 */
	private static Search splitQuery(String query, String indexuri) throws InvalidSearchException, TaskAbortException{
		if(query.matches("\\A\\w*\\Z")) {
			// single search term
			Request request = library.getIndex(indexuri).getTermEntries(query);
			if (request == null)
				throw new InvalidSearchException( "Something wrong with query=\""+query+"\" or indexURI=\""+indexuri+"\", maybe something is wrong with the index or it's uri is wrong." );
			return new Search(query, indexuri, request );
		}

		// Make phrase search
		if(query.matches("\\A\"[^\"]*\"\\Z")){
			ArrayList<Request> phrasesearches = new ArrayList();
			String[] phrase = query.replaceAll("\"(.*)\"", "$1").split(" ");
			Logger.minor(Search.class, "Phrase split"+query);
			for (String subquery : phrase){
				phrasesearches.add(splitQuery(subquery, indexuri));
			}
			return new Search(query, indexuri, phrasesearches, ResultOperation.PHRASE);
		}

		Logger.minor(Search.class, "Splitting " + query);
		String formattedquery="";
		// Remove phrases, place them in arraylist and replace tem with references to the arraylist
		ArrayList<String> phrases = new ArrayList();
		String[] phraseparts = query.split("\"");
		if(phraseparts.length>1)
			for (int i = 0; i < phraseparts.length; i++) {
				String string = phraseparts[i];
				formattedquery+=string;
				if (++i < phraseparts.length){
					string = phraseparts[i];
					formattedquery+="$"+phrases.size();
					phrases.add(string);
				}
			}
		else
			formattedquery=query;
		Logger.minor(Search.class, "phrases removed query : "+formattedquery);
		formattedquery = formattedquery.replaceAll("\\s+or\\s+", "||");
		formattedquery = formattedquery.replaceAll("\\s+(?:not\\s*|-)(\\S+)", "^^($1)");
		Logger.minor(Search.class, "not query : "+formattedquery);
		formattedquery = formattedquery.replaceAll("\\s+", "&&");
		Logger.minor(Search.class, "and query : "+formattedquery);

		// Put phrases back in
		phraseparts=formattedquery.split("\\$");
		formattedquery=phraseparts[0];
		for (int i = 1; i < phraseparts.length; i++) {
			String string = phraseparts[i];
			Logger.minor(Search.class, "replacing phrase "+string.replaceFirst("(\\d+).*", "$1"));
			formattedquery += "\""+ phrases.get(Integer.parseInt(string.replaceFirst("(\\d+).*", "$1"))) +"\"" + string.replaceFirst("\\d+(.*)", "$1");
		}
		Logger.minor(Search.class, "phrase back query : "+formattedquery);

		// Make complement search
		if (formattedquery.contains("^^(")){
			ArrayList<Request> complementsearches = new ArrayList();
			String[] splitup = formattedquery.split("(\\^\\^\\(|\\))", 3);
			complementsearches.add(splitQuery(splitup[0]+splitup[2], indexuri));
			complementsearches.add(splitQuery(splitup[1], indexuri));
			return new Search(query, indexuri, complementsearches, ResultOperation.REMOVE);
		}
		// Split intersections
		if (formattedquery.contains("&&")){
			ArrayList<Request> intersectsearches = new ArrayList();
			String[] intersects = formattedquery.split("&&");
			for (String subquery : intersects)
				intersectsearches.add(splitQuery(subquery, indexuri));
			return new Search(query, indexuri, intersectsearches, ResultOperation.INTERSECTION);
		}
		// Split Unions
		if (formattedquery.contains("||")){
			ArrayList<Request> unionsearches = new ArrayList();
			String[] unions = formattedquery.split("\\|\\|");
			for (String subquery : unions)
				unionsearches.add(splitQuery(subquery, indexuri));
			return new Search(query, indexuri, unionsearches, ResultOperation.UNION);
		}

		Logger.error(Search.class, "No split made, "+formattedquery+query);
		return null;
	}


	/**
	 * Sets the parent plugin to be used for logging & plugin api
	 */
	public static void setup(Library library){
		Search.library = library;
		Search.allsearches = new HashMap<String, Search>();
	}

	/**
	 * Gets a Search from the Map
	 * @param search
	 * @param indexuri
	 * @return Search or null if not found
	 */
	public static Search getSearch(String search, String indexuri){
		if(search==null || indexuri==null)
			return null;
		search = search.toLowerCase(Locale.US).trim();

		return allsearches.get(makeString(search, indexuri));
	}
	public static Search getSearch(int searchHash){
		return searchhashes.get(searchHash);
	}

	/**
	 * Looks for a given search in the map of searches
	 * @param search
	 * @param indexuri
	 * @return true if it's found
	 */
	public static boolean hasSearch(String search, String indexuri){
		if(search==null || indexuri==null)
			return false;
		search = search.toLowerCase(Locale.US).trim();
		return allsearches.containsKey(makeString(search, indexuri));
	}

	public static Map<String, Search> getAllSearches(){
		return allsearches;
	}

    public String getQuery(){
		return query;
	}

	public String getIndexURI(){
		return indexURI;
	}

	/**
	 * Creates a string which uniquly identifies this Search object for comparison
	 * and lookup, wont make false positives but can make false negatives as search and indexuri aren't standardised
	 * 
	 * @param search
	 * @param indexuri
	 * @return
	 */
	public static String makeString(String search, String indexuri){
		return search + "@" + indexuri;
	}

	@Override
	/**
	 * A descriptive string for logging
	 */
	public String toString(){
		return "Search: "+resultOperation+" : "+subject+" : "+subsearches;
	}

	/**
	 * @return List of Progresses this search depends on, it will not return CompositeProgresses
	 */
	@Override public List<? extends Progress> getSubProgress(){
		Logger.minor(this, toString());

		// Only index splits will allowed as composites
		if (resultOperation == ResultOperation.DIFFERENTINDEXES)
			return subsearches;
		// Everything else is split into leaves
		List<Progress> subprogresses = new ArrayList();
		for (Request<Set<TermEntry>> request : subsearches) {
			if( request instanceof CompositeProgress && ((CompositeProgress) request).getSubProgress()!=null && ((CompositeProgress) request).getSubProgress().iterator().hasNext()){
				for (Iterator<? extends Progress> it = ((CompositeRequest)request).getSubProgress().iterator(); it.hasNext();) {
					Progress progress1 = it.next();
					subprogresses.add(progress1);
				}
			}else
				subprogresses.add(request);
		}
		return subprogresses;
	}


	/**
	 * @return true if all are Finished, false otherwise
	 */
	@Override public boolean isDone() throws TaskAbortException{
		for(Request r : subsearches)
			if(!r.isDone())
				return false;
		return true;
	}

//	/**
//	 * @return sum of NumBlocksCompleted
//	 */
//	@Override public int partsDone() {
////		if(progressAccessed())
////			return blocksCompleted;
//		blocksCompleted=0;
//		for(Request r : subsearches)
//			blocksCompleted+=r.partsDone();
//		return blocksCompleted;
//	}
//
//	/**
//	 * @return sum of NumBlocksTotal
//	 * TODO record all these progress things to speed up
//	 */
//	@Override public int partsTotal() {
////		if(progressAccessed())
////			return blocksTotal;
//		blocksTotal=0;
//		for(Request r : subsearches)
//			blocksTotal+=r.partsTotal();
//		return blocksTotal;
//	}
//
//	/**
//	 * @return true if all subsearches numblocks are final
//	 */
//	@Override public boolean isTotalFinal() {
//		for(Request r : subsearches)
//			if(!r.isTotalFinal())
//				return false;
//		return true;
//	}

	/**
	 * Use ResultSet to perform Set operations on subsearches <br />
	 * @return Set of URIWrappers
	 */
	@Override public Set<TermEntry> getResult() throws TaskAbortException {
		if(!isDone())
			return null;

		status = SearchStatus.Combining;	// This wont show as this operation blocks
		
		allsearches.remove(getSubject());
		searchhashes.remove(hashCode());

		ResultSet result;
		result = new ResultSet(subject, resultOperation, subsearches);

		status = SearchStatus.Done;

		return result;
	}

	@Override
	public ProgressParts getParts() throws TaskAbortException {
		return ProgressParts.getParts(this.getSubProgress(), ProgressParts.ESTIMATE_UNKNOWN);
	}

	@Override
	public String getStatus() {
		try {
			if (status.compareTo(SearchStatus.Ready) < 0 && isDone()) {
				status = SearchStatus.Ready;
			}
		} catch (TaskAbortException ex) {
			return "Error";
		}
		return status.name();
	}

	public boolean isPartiallyDone() {
		throw new UnsupportedOperationException("Not supported yet.");
	}
}
