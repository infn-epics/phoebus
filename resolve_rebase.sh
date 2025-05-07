#!/bin/bash
cd /Users/michelottilabs/progetti/phoebus

for i in $(seq 1 100); do
    conflicted=$(git diff --name-only --diff-filter=U 2>/dev/null)
    if [ -z "$conflicted" ]; then
        if [ -d .git/rebase-merge ]; then
            git add -A
            result=$(GIT_EDITOR=":" git rebase --continue 2>&1)
            echo "$result" | tail -3
            if echo "$result" | grep -q "Successfully rebased"; then
                echo "REBASE_COMPLETE"
                exit 0
            fi
            continue
        else
            echo "REBASE_COMPLETE"
            exit 0
        fi
    fi

    pom_files=$(echo "$conflicted" | grep 'pom\.xml$' || true)
    other_files=$(echo "$conflicted" | grep -v 'pom\.xml$' || true)

    if [ -n "$pom_files" ]; then
        pom_count=$(echo "$pom_files" | wc -l | tr -d ' ')
        echo "[$i] Resolving $pom_count pom.xml with --ours (master)..."
        echo "$pom_files" | xargs git checkout --ours
    fi

    if [ -n "$other_files" ]; then
        other_count=$(echo "$other_files" | wc -l | tr -d ' ')
        echo "[$i] Resolving $other_count code files with --theirs (development)..."
        echo "$other_files" | xargs git checkout --theirs
    fi

    git add -A
    result=$(GIT_EDITOR=":" git rebase --continue 2>&1)

    if echo "$result" | grep -q "Successfully rebased"; then
        echo "REBASE_COMPLETE"
        exit 0
    fi

    commit_msg=$(cat .git/rebase-merge/message 2>/dev/null | head -1)
    echo "  Next: $commit_msg"
done

echo "LOOP_LIMIT_REACHED"
