# Keeps a Google Play review in progress safe from our own edits.
#
# Google Play's edits.commit takes changesInReviewBehavior. Left out, it is CANCEL_IN_REVIEW_AND_SUBMIT:
# the review in progress is cancelled, and the app goes to the back of the queue. fastlane supply 2.240.1
# never sends it and has no option for it. So every commit made through Google's Ruby client asks for
# ERROR_IF_IN_REVIEW here: while a review is in progress, Play refuses the commit, leaves the review alone,
# and the edit is deleted, so nothing is sent.
#
# Production also goes to review on its own. An edit that changes production and another track is deleted
# instead of committed, whatever made it.
# https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit
require "google/apis/androidpublisher_v3"
require "json"

module Crosstune
  module PlayReviewGuard
    BEHAVIOR = "ERROR_IF_IN_REVIEW"
    # The reason Play gives in the error's details, and the start of its message.
    REASON = "CHANGES_ALREADY_IN_REVIEW"
    PLAY_MESSAGE = "You already have changes in review"
    TITLE = "Google Play is reviewing the app"
    PRODUCTION = "production"
    MESSAGE = "Google Play is still reviewing changes sent before, so this job sent nothing to Google Play, " \
              "and the review goes on. Re-run this failed job when the review ends."

    # Play's refusal while a review is in progress.
    class InReview < StandardError; end

    # An edit that would send production and a testing track together.
    class Mixed < StandardError; end

    # The tracks each open edit changes, by edit id.
    def self.tracks
      @tracks ||= Hash.new { |hash, edit_id| hash[edit_id] = [] }
    end

    def self.in_review?(error)
      return false unless error.respond_to?(:status_code) && error.status_code == 400

      body = JSON.parse(error.body.to_s)["error"] || {}
      details = body["details"] || []
      details.any? { |detail| detail["reason"] == REASON } ||
        (body["status"] == "FAILED_PRECONDITION" && body["message"].to_s.start_with?(PLAY_MESSAGE))
    rescue JSON::ParserError
      false
    end

    # Play keeps the refused edit. Deleting it leaves nothing behind; if that fails too, the edit expires
    # by itself, and the review is safe either way.
    def self.discard(service, package_name, edit_id)
      service.delete_edit(package_name, edit_id)
    rescue Google::Apis::Error => e
      warn("The refused edit #{edit_id} wasn't deleted (#{e.message}). It expires by itself.")
    end

    def self.refuse!(title, message, error)
      warn("::error title=#{title}::#{message}") if ENV["GITHUB_ACTIONS"] == "true"
      # fastlane prints its own errors plainly, with no stack trace.
      FastlaneCore::UI.user_error!(message) if defined?(FastlaneCore::UI)
      raise error, message
    end

    def self.check_tracks!(service, package_name, edit_id)
      changed = tracks.fetch(edit_id, []).uniq
      return unless changed.include?(PRODUCTION) && changed.size > 1

      discard(service, package_name, edit_id)
      refuse!("Production goes to review on its own",
              "This edit changed production and #{(changed - [PRODUCTION]).join(', ')}, so it was deleted, and " \
              "nothing was sent to Google Play. Production goes to review on its own.", Mixed)
    end

    # Fails at once if this version of Google's client can't ask Play to keep a review, rather than
    # committing without it.
    def self.check!(service_class)
      keywords = service_class.instance_method(:commit_edit).parameters.map(&:last)
      return if keywords.include?(:changes_in_review_behavior)

      raise "google-apis-androidpublisher_v3 #{Gem.loaded_specs['google-apis-androidpublisher_v3']&.version} " \
            "can't send changesInReviewBehavior, so a commit would cancel a Google Play review in progress"
    end

    def update_edit_track(package_name, edit_id, track, *args, **options, &block)
      PlayReviewGuard.tracks[edit_id] << track
      super
    end

    def patch_edit_track(package_name, edit_id, track, *args, **options, &block)
      PlayReviewGuard.tracks[edit_id] << track
      super
    end

    def commit_edit(package_name, edit_id, **options, &block)
      PlayReviewGuard.check_tracks!(self, package_name, edit_id)
      begin
        super(package_name, edit_id, **options, changes_in_review_behavior: BEHAVIOR, &block)
      rescue Google::Apis::ClientError => e
        raise unless PlayReviewGuard.in_review?(e)

        PlayReviewGuard.discard(self, package_name, edit_id)
        PlayReviewGuard.refuse!(TITLE, MESSAGE, InReview)
      end
    end
  end
end

Crosstune::PlayReviewGuard.check!(Google::Apis::AndroidpublisherV3::AndroidPublisherService)
Google::Apis::AndroidpublisherV3::AndroidPublisherService.prepend(Crosstune::PlayReviewGuard)
