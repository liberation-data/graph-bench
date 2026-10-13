package bench

import org.drivine.annotation.Count
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.ReadOnly
import org.drivine.annotation.Root

/*
 * A code-collaboration graph:
 *
 *   Person -[:MEMBER_OF]-> Org          Person -[:FOLLOWS]-> Person
 *   Repo -[:OWNED_BY]-> Org             Repo -[:DEPENDS_ON]-> Repo
 *   Issue -[:IN_REPO]-> Repo            Issue -[:OPENED_BY]-> Person
 *   Issue -[:ASSIGNED_TO]-> Person      Issue -[:TAGGED]-> Topic
 *   Comment -[:ON]-> Issue              Comment -[:WRITTEN_BY]-> Person
 */

@NodeFragment(labels = ["Org"])
data class Org(@NodeId val id: String, val name: String, val country: String)

@NodeFragment(labels = ["Person"])
data class Person(@NodeId val id: String, val name: String, val email: String, val karma: Int)

@NodeFragment(labels = ["Repo"])
data class Repo(@NodeId val id: String, val name: String, val language: String, val stars: Int)

@NodeFragment(labels = ["Topic"])
data class Topic(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["Issue"])
data class Issue(
    @NodeId val id: String,
    val title: String,
    val state: String,
    val priority: Int,
    val createdAt: Long,
    /** The id of the repo the issue is in, kept on the node and indexed. */
    val repoKey: String,
)

@NodeFragment(labels = ["Comment"])
data class Comment(@NodeId val id: String, val body: String, val createdAt: Long)

/** An issue with everything one hop from it: five relationships, three of them to-many. */
@GraphView
data class IssueDetail(
    @Root val issue: Issue,
    @GraphRelationship(type = "IN_REPO", direction = Direction.OUTGOING)
    val repo: Repo,
    @GraphRelationship(type = "OPENED_BY", direction = Direction.OUTGOING)
    val author: Person,
    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
    val assignees: List<Person> = emptyList(),
    @GraphRelationship(type = "TAGGED", direction = Direction.OUTGOING)
    val topics: List<Topic> = emptyList(),
    @GraphRelationship(type = "ON", direction = Direction.INCOMING)
    val comments: List<Comment> = emptyList(),
)

@GraphView
data class CommentWithAuthor(
    @Root val comment: Comment,
    @GraphRelationship(type = "WRITTEN_BY", direction = Direction.OUTGOING)
    val author: Person,
)

/** A view of views: an issue, its comments, and each comment's author. */
@GraphView
data class IssueThread(
    @Root val issue: Issue,
    @GraphRelationship(type = "ON", direction = Direction.INCOMING)
    val comments: List<CommentWithAuthor> = emptyList(),
)

@GraphView
data class RepoOverview(
    @Root val repo: Repo,
    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: Org,
    @GraphRelationship(type = "DEPENDS_ON", direction = Direction.OUTGOING)
    val dependencies: List<Repo> = emptyList(),
    @ReadOnly
    @Count("IN_REPO", direction = Direction.INCOMING)
    val issueCount: Long = 0,
)

/** The orgs of the people a person follows: a two-hop path that maps only its far end. */
@GraphView
data class PersonReach(
    @Root val person: Person,
    @ReadOnly
    @GraphPath([
        Hop("FOLLOWS", Direction.OUTGOING, label = "Person"),
        Hop("MEMBER_OF", Direction.OUTGOING),
    ])
    val orgs: List<Org> = emptyList(),
)

/** What the write workloads save: an issue and the comments on it. */
@GraphView
data class IssueComments(
    @Root val issue: Issue,
    @GraphRelationship(type = "ON", direction = Direction.INCOMING)
    val comments: List<Comment> = emptyList(),
)
