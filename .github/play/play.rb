# How play.sh runs fastlane: always with review_guard.rb loaded first, so no commit can cancel a review, and
# no edit can send production and a testing track to review together. Every edit opened here is deleted unless
# Play took it: after a read or a check, and when anything stops it.
#
#   ruby play.rb supply ARGS...
#     fastlane supply, as on the command line.
#   ruby play.rb plan KEY PACKAGE TRACK[,TRACK...] CODE AAB TAGS
#     What a release of AAB as CODE does, from what Play has on each TRACK (internal testing first) and on
#     production. TAGS is the version code of each tag, with commas between. It only reads.
#     It stops when a version code on Play is one no tag has, at any height: a bundle sent by hand, a draft in
#     Play Console, or a tag pushed and then deleted.
#     With a version code above CODE on any track, as for a fix to an older version or an older tag run
#     again, Play gets nothing: it exits 5 when production has CODE or newer, else 6, for promote.yml to send
#     to production.
#     Else it exits 0 when the first TRACK has AAB itself as CODE: a re-run. 4 when Play has AAB as CODE, but
#     not there: sent in a run whose commit Play refused. 3 when Play has no bundle as CODE. It stops when Play
#     has another bundle as CODE (sent by hand, or the same tag built or signed again).
#   ruby play.rb on-track KEY PACKAGE TRACK[,TRACK...] CODE
#     Exits 0 when the first TRACK has a release with CODE. 3 when it doesn't, but a TRACK has a newer
#     version: CODE is a fix to an older version, which only production takes. Else it stops. It only reads.
#   ruby play.rb edit KEY PACKAGE [[--from TRACK] --code CODE --to TRACK[,TRACK...]] [--aab AAB]
#                     [--listing DIR] [--notes DIR] [--check-only]
#     One edit. With --from, the release CODE on that track goes on to each --to track. With no --from, the
#     bundle as CODE goes on them, and none may have it already: AAB, sent in this edit unless Play has it, or
#     else the bundle Play has. Either way, it stops when a --to track has a version code above CODE. With the
#     listing in DIR (folders by language, as play.sh makes them) and the release notes in
#     DIR/en-US/changelogs/CODE.txt. Then the edit is committed, or with --check-only only checked. supply can
#     only promote to one track a run, so this drives supply's own client and uploader in one edit.
require "digest"
require "optparse"
require_relative "review_guard"

module Crosstune
  module Play
    PRODUCTION = "production"

    # A plain stop, as an annotation on the job too.
    def self.stop!(message)
      warn("::error::#{message}") if ENV["GITHUB_ACTIONS"] == "true"
      FastlaneCore::UI.user_error!(message)
    end

    # supply's uploader, with the client that holds the edit in reach.
    class Edit
      def self.uploader_class
        @uploader_class ||= Class.new(Supply::Uploader) { public :client }
      end

      def initialize(options)
        Supply.config = FastlaneCore::Configuration.create(Supply::Options.available_options, options)
        @uploader = self.class.uploader_class.new
      end

      def client
        @uploader.client
      end

      # Runs the block in an edit of its own, and deletes the edit unless Play took it. One the review guard
      # refused is deleted already.
      def within(package)
        client.begin_edit(package_name: package)
        yield
      ensure
        open = client.current_edit
        discard(open) if open && !PlayReviewGuard.discarded.include?(open.id)
      end

      def discard(open)
        client.abort_current_edit
      rescue StandardError => e
        warn("The edit #{open.id} wasn't deleted (#{e.message}). It expires by itself.")
      end

      # The version codes on each track named, in the order named.
      def version_codes(names)
        found = client.tracks(*names).to_h do |track|
          [track.track, track.releases.to_a.flat_map { |release| release.version_codes.to_a }.map(&:to_i)]
        end
        names.to_h { |name| [name, found.fetch(name, [])] }
      end

      # "track (code)" for each track with a version code above code: a release there would take it back.
      def newer(codes, code)
        codes.filter_map { |name, on| "#{name} (#{on.max})" if on.any? { |c| c > code.to_i } }
      end

      def bundles
        client.client.list_edit_bundles(client.current_package_name, client.current_edit.id).bundles.to_a
      end

      # :absent, or :same when Play holds this very bundle as code. Another bundle as code stops the job.
      def held(bundles, code, aab)
        bundle = bundles.find { |b| b.version_code.to_s == code }
        return :absent unless bundle
        return :same if bundle.sha256.to_s.casecmp?(Digest::SHA256.file(aab).hexdigest)

        FastlaneCore::UI.user_error!("Google Play already has another bundle as version code #{code}, and it " \
                                     "takes each version code once. Release this as a new version, with a new tag.")
      end

      # What a release does: see plan at the top.
      def plan(package, tracks, code, aab, tags)
        codes, bundles = within(package) { [version_codes(tracks + [PRODUCTION]), self.bundles] }
        stop_strays(codes, tags + [code.to_i])
        newer = newer(codes, code)
        unless newer.empty?
          puts("Google Play has a version newer than #{code} on #{newer.join(', ')}")
          return codes[PRODUCTION].any? { |c| c >= code.to_i } ? 5 : 6
        end
        return 3 if held(bundles, code, aab) == :absent

        codes[tracks.first].include?(code.to_i) ? 0 : 4
      end

      # A version code on Play that no tag has, whether above or below the newest: the job can't tell what it
      # is, or whether a release would take a track back past it.
      def stop_strays(codes, tags)
        stray = codes.flat_map { |name, on| (on - tags).uniq.map { |c| "#{c} on #{name}" } }
        return if stray.empty?

        Play.stop!("Google Play has version code #{stray.join(', ')}, and no tag has it: a bundle sent by hand, a " \
                   "draft in Play Console, or a tag pushed and then deleted. Nothing was sent. Take it off in Play " \
                   "Console, or push its tag, then re-run this job.")
      end

      # Production's way for CODE: see on-track at the top.
      def on_track(package, tracks, code)
        codes = within(package) { version_codes(tracks) }
        return 0 if codes[tracks.first].include?(code.to_i)

        newer = codes.find { |_, on| on.any? { |c| c > code.to_i } }&.first
        Play.stop!("Track '#{tracks.first}' has no release with version code #{code}. Its tag's release sends it " \
                   "there: re-run it, then run this again.") unless newer
        puts("Track '#{newer}' has a newer version than #{code}")
        3
      end

      def stop_going_back(to, code)
        newer = newer(version_codes(to), code)
        return if newer.empty?

        FastlaneCore::UI.user_error!("Google Play already has a version newer than #{code} on #{newer.join(', ')}, " \
                                     "so nothing was sent: a release never takes a track back to an older version")
      end

      # The bundle as code, as the one release on each track, as supply's upload puts it there: aab, sent in
      # this edit unless Play has it, or else the bundle Play has. Only on tracks with older versions, which
      # stop_going_back checks first: a fix to an older version goes on production this way, in one edit with
      # its bundle.
      def put(code, to, aab)
        has = version_codes(to).select { |_, on| on.include?(code.to_i) }.keys
        FastlaneCore::UI.user_error!("#{has.join(', ')} has #{code} already, so nothing was sent") unless has.empty?
        send_bundle(code, aab)

        release = Google::Apis::AndroidpublisherV3::TrackRelease.new(
          status: Supply::ReleaseStatus::COMPLETED,
          version_codes: [code]
        )
        to.each do |name|
          track = client.tracks(name).first || Google::Apis::AndroidpublisherV3::Track.new(track: name)
          track.releases = [release]
          client.update_track(name, track)
        end
      end

      def send_bundle(code, aab)
        if aab
          return if held(bundles, code, aab) == :same

          sent = client.upload_bundle(aab)
          return if sent.to_s == code

          FastlaneCore::UI.user_error!("Google Play reads #{sent} as the bundle's version code, not #{code}, so " \
                                       "nothing was sent")
        end
        return if bundles.any? { |b| b.version_code.to_s == code }

        FastlaneCore::UI.user_error!("Google Play has no bundle as #{code}, so nothing was sent. Its tag's " \
                                     "release sends it")
      end

      # supply's promote_track, for each track, but one that skips a release with no version codes, such as a
      # draft started in Play Console, where supply's stops with a NoMethodError.
      def promote(from, code, to)
        releases = client.tracks(from).first&.releases.to_a
        releases = releases.select { |r| r.version_codes.to_a.map(&:to_s).include?(code) }
        FastlaneCore::UI.user_error!("Track '#{from}' has no release with version code #{code}") if releases.empty?
        if releases.size > 1
          FastlaneCore::UI.user_error!("Track '#{from}' has #{releases.size} releases with version code #{code}")
        end

        release = releases.first

        to.each do |name|
          promoted = release.dup
          promoted.status = Supply::ReleaseStatus::COMPLETED
          promoted.user_fraction = nil
          track = client.tracks(name).first || Google::Apis::AndroidpublisherV3::Track.new(track: name)
          # Play keeps the track's other releases as they are.
          track.releases = [promoted]
          client.update_track(name, track)
        end
      end

      # Each language's words and pictures. sync_image_upload sends only the pictures that changed.
      def send_listing(listing)
        Dir.children(listing).sort.each do |language|
          @uploader.upload_metadata(language, client.listing_for_language(language))
          @uploader.upload_images(language)
          @uploader.upload_screenshots(language)
        end
      end

      def send_notes(track)
        @uploader.perform_upload_meta([], track)
      end
    end

    module_function

    def require_supply
      require "supply"
    end

    def plan(argv)
      key, package, tracks, code, aab, tags = argv
      abort("usage: play.rb plan KEY PACKAGE TRACK[,TRACK...] CODE AAB TAGS") unless tags
      require_supply
      tags = tags.split(",").map { |tag| Integer(tag, 10) }
      exit(Edit.new(json_key: key, package_name: package).plan(package, tracks.split(","), code, aab, tags))
    end

    def on_track(argv)
      key, package, tracks, code = argv
      abort("usage: play.rb on-track KEY PACKAGE TRACK[,TRACK...] CODE") unless code
      require_supply
      exit(Edit.new(json_key: key, package_name: package).on_track(package, tracks.split(","), code))
    end

    def parse_edit(argv)
      key, package, *rest = argv
      options = { to: [] }
      OptionParser.new do |parser|
        parser.on("--from TRACK") { |value| options[:from] = value }
        parser.on("--code CODE") { |value| options[:code] = value }
        parser.on("--to TRACKS", Array) { |value| options[:to] = value.reject(&:empty?) }
        parser.on("--aab AAB") { |value| options[:aab] = value }
        parser.on("--listing DIR") { |value| options[:listing] = value }
        parser.on("--notes DIR") { |value| options[:notes] = value }
        parser.on("--check-only") { options[:check_only] = true }
      end.parse!(rest)
      abort("usage: play.rb edit KEY PACKAGE ...") unless package && rest.empty?
      options.merge(key: key, package: package)
    end

    def check_edit(options)
      to = options[:to]
      abort("Name --code with --to") if !to.empty? && !options[:code]
      abort("Name --to with --code") if to.empty? && options[:code]
      abort("An App Bundle goes on tracks with no --from") if options[:aab] && (to.empty? || options[:from])
      abort("Release notes go to one track at a time") if options[:notes] && to.size != 1
      abort("Release notes and the listing go in separate edits") if options[:notes] && options[:listing]
      abort("Nothing to send") if to.empty? && !options[:listing]
    end

    def edit(argv)
      options = parse_edit(argv)
      check_edit(options)
      require_supply
      edit = Edit.new(
        json_key: options[:key],
        package_name: options[:package],
        track: options[:from] || "internal",
        version_code: options[:code]&.to_i,
        metadata_path: options[:notes] || options[:listing],
        skip_upload_apk: true,
        skip_upload_aab: true,
        skip_upload_metadata: true,
        skip_upload_images: true,
        skip_upload_screenshots: true,
        sync_image_upload: true
      )
      edit.within(options[:package]) { change(edit, options) }
    end

    def change(edit, options)
      if options[:from]
        edit.stop_going_back(options[:to], options[:code])
        edit.promote(options[:from], options[:code], options[:to])
      elsif !options[:to].empty?
        edit.stop_going_back(options[:to], options[:code])
        edit.put(options[:code], options[:to], options[:aab])
      end
      edit.send_notes(options[:to].first) if options[:notes]
      edit.send_listing(options[:listing]) if options[:listing]
      if options[:check_only]
        edit.client.validate_current_edit!
        puts("Google Play would take it")
      else
        edit.client.commit_current_edit!
        puts("Sent to Google Play")
      end
    end

    def main(argv)
      case argv.shift
      when "supply"
        argv.unshift("supply")
        load Gem.bin_path("fastlane", "fastlane")
      when "plan" then plan(argv)
      when "on-track" then on_track(argv)
      when "edit" then edit(argv)
      else
        abort("usage: play.rb supply ARGS... | plan KEY PACKAGE TRACK[,TRACK...] CODE AAB TAGS | " \
              "on-track KEY PACKAGE TRACK[,TRACK...] CODE | edit KEY PACKAGE ...")
      end
    end
  end
end

begin
  Crosstune::Play.main(ARGV)
rescue StandardError => e
  # Plainly, as fastlane's own command line prints its errors. Anything else is a bug here, with its stack trace.
  plain = defined?(FastlaneCore::Interface) &&
          [FastlaneCore::Interface::FastlaneError, FastlaneCore::Interface::FastlaneCommonException].any? do |c|
            e.is_a?(c)
          end
  if plain
    warn("[!] #{e.message}")
  elsif e.is_a?(Google::Apis::Error)
    warn("[!] Google Play: #{[e.status_code, e.body || e.message].compact.join(' ')}")
  else
    raise
  end
  exit(1)
end
